package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DOWNLOAD_DEBUG
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadParallelismKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = StreamUrlCache()
    private val reservedDownloadIds = ConcurrentHashMap.newKeySet<String>()
    private val downloadScope = CoroutineScope(dlCoroutine)
    private val registryMutex = Mutex()
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .addNetworkInterceptor { chain ->
                            val response = chain.proceed(chain.request())
                            if (response.code in RETRYABLE_STREAM_RESPONSE_CODES) {
                                songUrlCache.invalidateUrl(chain.request().url.toString())?.let { rejected ->
                                    Log.w(TAG, "Invalidated rejected ${rejected.clientName} download stream: HTTP ${response.code}")
                                }
                            }
                            response
                        }
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val contentLength = playerCache.getContentMetadata(mediaId)
            .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
        val cachedRangeLength = requiredCachedStreamLength(dataSpec.length, dataSpec.position, contentLength)
        if (cachedRangeLength != null && playerCache.isCached(mediaId, dataSpec.position, cachedRangeLength)) {
            return@Factory dataSpec
        }

        songUrlCache[mediaId]?.let { cachedStream ->
            return@Factory dataSpec.withResolvedStream(cachedStream)
        }

        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlaybackWithRetry(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                rejectedClient = songUrlCache.rejectedClient(mediaId),
            )
        }.getOrThrow()
        val format = playbackData.format

        format.contentLength?.let { contentLength ->
            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.substringBefore(";"),
                        codecs = format.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = contentLength,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
        }

        val streamUrl = format.contentLength?.let { contentLength ->
            // Keep the bounded range when YouTube reports a real length; never truncate an
            // unknown-length track to an arbitrary 10 MB fallback.
            "${playbackData.streamUrl}&range=0-$contentLength"
        } ?: playbackData.streamUrl

        val stream = songUrlCache.put(
            mediaId = mediaId,
            url = streamUrl,
            requestHeaders = playbackData.streamHeaders,
            clientName = playbackData.streamClient,
            expiresInSeconds = playbackData.streamExpiresInSeconds,
        )
        dataSpec.withResolvedStream(stream)
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = context.dataStore.get(DownloadParallelismKey, 1).coerceIn(1, 3)
            requirements = downloadRequirements(context.dataStore.get(DownloadOnWifiOnlyKey, true))
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
        }
    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )
    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    fun isDownloadCompleted(songId: String): Boolean = try {
        downloadManager.downloadIndex.getDownload(songId)?.let { hasCompleteDownloadCache(downloadCache, it) } == true
    } catch (exception: IOException) {
        Log.w(TAG, "Unable to read download state: ${exception.javaClass.simpleName}")
        false
    }

    fun download(songs: List<MediaMetadata>) {
        queueEligibleDownloads(songs.map { DownloadCandidate(it.id, it.title, isLocal = it.isLocal) })
    }

    fun download(song: MediaMetadata) {
        download(listOf(song))
    }

    fun download(song: SongEntity) {
        queueEligibleDownloads(listOf(DownloadCandidate(song.id, song.title, isLocal = song.isLocal)))
    }

    fun setDownloadParallelism(value: Int) {
        downloadManager.maxParallelDownloads = value.coerceIn(1, 3)
    }

    fun removeDownloads(mediaIds: List<String>, cancelOnly: Boolean) {
        if (mediaIds.isEmpty()) return
        downloadScope.launch {
            try {
                val states = mutableMapOf<String, Int>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) states[cursor.download.request.id] = cursor.download.state
                }
                if (!cancelOnly) localMgr.getAvailableFiles(false)
                for (id in planDownloadRemoval(mediaIds, states, cancelOnly)) {
                    try {
                        if (cancelOnly) {
                            // Stopping, rather than removing, also preserves a track that finished
                            // between the index snapshot and service processing this command.
                            DownloadService.sendSetStopReason(
                                context, ExoDownloadService::class.java, id, STOP_REASON_USER_CANCELLED, false,
                            )
                        } else {
                            if (localMgr.getFilePathIfExists(id) != null && !deleteSong(id)) {
                                throw IOException("Unable to remove downloaded file")
                            }
                            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Unable to remove download $id", error)
                        withContext(Dispatchers.Main) { Toast.makeText(context, R.string.error_unknown, LENGTH_SHORT).show() }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to inspect downloads for removal", error)
                withContext(Dispatchers.Main) { Toast.makeText(context, R.string.error_unknown, LENGTH_SHORT).show() }
            }
        }
    }

    /**
     * Update the network requirement for downloads. Takes effect immediately, including for
     * downloads that are already queued or in progress.
     */
    fun setDownloadRequirements(wifiOnly: Boolean) {
        DownloadService.sendSetRequirements(
            context,
            ExoDownloadService::class.java,
            downloadRequirements(wifiOnly),
            false
        )
    }

    /**
     * Show a hint when a download is requested on a metered network while Wi-Fi only is enabled,
     * since the download is queued silently and only starts once Wi-Fi is available.
     */
    private fun notifyIfWaitingForWifi() {
        if (context.dataStore.get(DownloadOnWifiOnlyKey, true) && connectivityManager.isActiveNetworkMetered) {
            Toast.makeText(context, R.string.download_waiting_for_wifi, LENGTH_SHORT).show()
        }
    }

    private fun queueEligibleDownloads(songs: List<DownloadCandidate>) {
        if (songs.isEmpty()) return
        downloadScope.launch {
            try {
                val localFileIds = localMgr.getAvailableFiles(false).keys
                val recordedDownloads = downloads.value
                val recordedDatabaseIds = database.downloadedOrQueuedSongs().first().mapTo(mutableSetOf()) { it.id }
                val indexedDownloads = mutableMapOf<String, Download>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) indexedDownloads[cursor.download.request.id] = cursor.download
                }
                val candidates = songs.distinctBy { it.id }.map { song ->
                    val indexed = indexedDownloads[song.id]
                    song.copy(
                        hasLocalFile = song.id in localFileIds,
                        state = indexed?.state,
                        hasCompleteCache = indexed?.state == Download.STATE_COMPLETED &&
                            hasCompleteDownloadCache(downloadCache, indexed),
                        hasRecordedDownload = recordedDownloads[song.id] != null || song.id in recordedDatabaseIds,
                        isReserved = song.id in reservedDownloadIds,
                    )
                }
                val plan = planDownloads(candidates)
                Log.i(TAG, "Download preflight: downloaded=${plan.alreadyDownloaded}, queued=${plan.alreadyQueued}, " +
                    "toQueue=${plan.toQueue.size}, unavailable=${plan.unavailable}")
                if (plan.staleMarkers.isNotEmpty()) {
                    downloads.update { it - plan.staleMarkers }
                    plan.staleMarkers.forEach { database.updateDownloadStatus(it, null) }
                }

                val toQueue = plan.toQueue.filter { reservedDownloadIds.add(it.id) }
                if (toQueue.isNotEmpty()) withContext(Dispatchers.Main) { notifyIfWaitingForWifi() }
                toQueue.forEach { song ->
                    try {
                        // Failed downloads must resolve a new stream URL on manual retry.
                        songUrlCache.invalidate(song.id)
                        val request = DownloadRequest.Builder(song.id, song.id.toUri())
                            .setCustomCacheKey(song.id)
                            .setData(song.title.toByteArray())
                            .build()
                        DownloadService.sendAddDownload(
                            context,
                            ExoDownloadService::class.java,
                            request,
                            false,
                        )
                    } catch (exception: Exception) {
                        reservedDownloadIds.remove(song.id)
                        Log.e(TAG, "Unable to queue download ${song.id}", exception)
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to inspect downloads before queueing", exception)
            }
        }
    }

    fun resumeDownloadsOnStart() {
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }


// Deletes from custom dl

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: SongItem) = deleteSong(song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    private fun deleteSong(id: String): Boolean {
        val deleted = localMgr.deleteFile(id)
        if (!deleted) return false
        downloads.update { map ->
            map.toMutableMap().apply {
                remove(id)
            }
        }

        runBlocking {
            database.song(id).first()?.song?.copy(localPath = null)?.let { database.update(it) }
            database.updateDownloadStatus(id, null)
        }
        return true
    }

    /**
     * Migrated existing downloads from the download cache to the new system in external storage
     */
    suspend fun migrateDownloads() {
        if (!isProcessingDownloads.compareAndSet(expect = false, update = true)) return

        var runs = 0
        try {
            val downloadedSongs = mutableMapOf<String, Download>()
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) downloadedSongs[cursor.download.request.id] = cursor.download
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    if (DOWNLOAD_DEBUG) Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val displayName = database.song(s.key).first()?.title.orEmpty()
                migrateCachedDownload(downloadCache, s.value) { data ->
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = data,
                        displayName = displayName,
                    )
                }
            }
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isProcessingDownloads.value = false
        }
        scanDownloads()
    }


    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() = reconcileDownloads()

    suspend fun scanDownloads() = reconcileDownloads()

    private suspend fun reconcileDownloads() {
        registryMutex.withLock {
            if (!isProcessingDownloads.compareAndSet(expect = false, update = true)) return
            try {
                // Discovery must finish before stale markers can be cleared.
                val externalFiles = localMgr.getAvailableFiles(false)
                val externalPaths = externalFiles.mapValues { (_, uri) ->
                    fileFromUri(context, uri)?.absolutePath
                        ?: throw IOException("Unable to resolve downloaded file")
                }
                val recorded = database.downloadedOrQueuedSongs().first().associateBy { it.id }
                val indexed = mutableMapOf<String, Download>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) indexed[cursor.download.request.id] = cursor.download
                }
                val timeNow = LocalDateTime.now()
                val ids = recorded.keys + indexed.keys + externalFiles.keys
                val states = ids.associateWith { id ->
                    val externalDate = if (id in externalFiles) {
                        recorded[id]?.song?.dateDownload?.takeIf { it > STATE_DOWNLOADING } ?: timeNow
                    } else null
                    downloadRegistryState(downloadCache, indexed[id], externalDate)
                }
                publishDownloadRegistry(
                    database, states, externalPaths,
                    recorded.filterValues { it.song.localPath != null }.keys, downloads,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportException(error)
            } finally {
                isProcessingDownloads.value = false
            }
        }
    }

    companion object {
        private const val STOP_REASON_USER_CANCELLED = 1
        private val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()

        fun downloadRequirements(wifiOnly: Boolean): Requirements =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }


    init {
        if (DOWNLOAD_DEBUG) Log.i(TAG, "DownloadUtil init")
        // TODO: make sure db is update when download is queued
        CoroutineScope(dlCoroutine).launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    reservedDownloadIds.remove(download.request.id)
                    downloadScope.launch {
                        registryMutex.withLock {
                            try {
                                val id = download.request.id
                                val current = downloadManager.downloadIndex.getDownload(id)
                                val existing = database.song(id).first()?.song
                                val internalState = downloadRegistryState(downloadCache, current)
                                var externalUnknown = false
                                val external = try {
                                    localMgr.ensureDirectoriesReadable()
                                    if (internalState == null) localMgr.getAvailableFiles(false)[id]
                                    else localMgr.getValidatedFilePathIfExists(id)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (internalState == null) throw error
                                    reportException(error)
                                    externalUnknown = true
                                    null
                                }
                                val externalDate = if (external != null ||
                                    (externalUnknown && existing?.localPath != null)) {
                                    existing?.dateDownload?.takeIf { it > STATE_DOWNLOADING }
                                        ?: if (external != null) LocalDateTime.now() else null
                                } else null
                                val state = downloadRegistryState(downloadCache, current, externalDate)
                                database.withTransferTransaction {
                                    if (state == null) {
                                        removeDownloadSong(id)
                                    } else {
                                        if (!externalUnknown && external == null && existing?.localPath != null) {
                                            removeDownloadSong(id)
                                        }
                                        updateDownloadStatus(id, state)
                                    }
                                }
                                downloads.update { map ->
                                    if (state == null) map - id else map + (id to state)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                reportException(error)
                            }
                        }
                    }
                }
            }
        )
    }
}
