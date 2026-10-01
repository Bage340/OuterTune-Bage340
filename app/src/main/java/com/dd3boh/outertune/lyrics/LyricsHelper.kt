package com.dd3boh.outertune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Role of a remote fetch, used to correlate log lines when several fetches run for different songs.
 */
enum class LyricsFetchRole(val log: String) {
    CURRENT("current"),
    PREFETCH("prefetch"),
    MANUAL("manual"),
}

@Singleton
class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val lyricsProviders =
        listOf(
            YouTubeLyricsProvider,
            LrcLibLyricsProvider,
            BetterLyricsProvider,
            SimpMusicLyricsProvider,
            KuGouLyricsProvider,
            YouTubeSubtitleLyricsProvider,
        )
    private val cache = LruCache<ManualLyricsCacheKey, List<LyricsResult>>(MAX_CACHE_SIZE)
    private val fetchCoordinator = LyricsFetchCoordinator()
    val fetchStates = fetchCoordinator.states

    /**
     * Per-videoId mutexes that make [fetchAndStoreRemote] single-flight: at most one fetch runs for a
     * given videoId at a time, and each fetch re-checks the database under the lock before starting.
     * Entries are never removed, so the map grows by the number of songs fetched during the process
     * lifetime; it is released when the process ends.
     */
    private val fetchMutexes = HashMap<String, Mutex>()
    private val fetchMutexesGuard = Mutex()

    private suspend fun fetchMutexFor(videoId: String): Mutex =
        fetchMutexesGuard.withLock { fetchMutexes.getOrPut(videoId) { Mutex() } }

    /**
     * Resolve lyrics for a song from the stored database row, the local .lrc file and the remote
     * providers, in an order controlled by the source preference (LyricSourcePrefKey): when local
     * lyrics are preferred the local file is tried first; otherwise the stored row wins and
     * LYRICS_NOT_FOUND resolves to null. When neither source has lyrics, a remote fetch runs and
     * the freshly stored result is returned, falling back to the local file when remote lyrics
     * are preferred but none are found.
     *
     * @param mediaMetadata song to resolve lyrics for
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val parserOptions = getParserOptions()
        val prefLocal = isLocalPreferred()

        val dbEntity = database.lyrics(mediaMetadata.id).first()
        val dbLyrics = dbEntity?.lyrics
        val hasPositive = dbLyrics != null && dbLyrics != LYRICS_NOT_FOUND
        val localLyrics = if (prefLocal || !hasPositive) getLocalLyrics(mediaMetadata, parserOptions) else null
        if (prefLocal && localLyrics != null) return localLyrics
        if (hasPositive) {
            var usable = dbLyrics
            if (preferredFailureIn(dbEntity?.providerSignature) != null && shouldFetch(mediaMetadata.id)) {
                fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL)
                usable = database.lyrics(mediaMetadata.id).first()?.lyrics
                    ?.takeUnless { it == LYRICS_NOT_FOUND } ?: dbLyrics
            }
            return LrcUtils.parseLyrics(usable, null, parserOptions, null)
        }

        // No usable positive cache in the preferred source. Fetch only when there is no row or the
        // negative cache is stale/invalid; a fresh negative cache is not re-fetched. LYRICS_NOT_FOUND is
        // never treated as a plain "row exists".
        if (shouldFetch(mediaMetadata.id)) {
            fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL)
            val fetched = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
            if (fetched != null && fetched != LYRICS_NOT_FOUND) {
                return LrcUtils.parseLyrics(fetched, null, parserOptions, null)
            }
        }
        return if (!prefLocal) localLyrics else null
    }

    /**
     * Whether a remote fetch should run for [videoId] right now: true when there is no row, when
     * [forceRefresh] is set, or when the stored row is a negative cache that is stale, was written under
     * a different provider configuration, or predates the signature columns. A positive cache is kept.
     */
    suspend fun shouldFetch(videoId: String, forceRefresh: Boolean = false): Boolean {
        val entity = database.lyrics(videoId).first()
        val signature = ProviderSelection.snapshot(context, lyricsProviders).signature
        return shouldFetchLyrics(entity, signature, System.currentTimeMillis(), forceRefresh)
    }

    /**
     * Resolve lyrics for [mediaMetadata] from the remote providers and store the outcome.
     *
     * Single-flight per videoId: runs under a per-videoId lock and re-checks [shouldFetchLyrics] under
     * the lock so a concurrent fetch that already resolved this song is not repeated. A usable result
     * (Found) is stored with its provider and metadata, and a unanimous absence (DefinitiveNotFound) is
     * stored as a negative cache; Indeterminate and Skipped leave any existing row untouched, so a
     * transient failure never becomes a persistent negative cache, even with [forceRefresh].
     *
     * @param role which caller started this fetch; current/manual requests also publish UI state
     */
    suspend fun fetchAndStoreRemote(
        mediaMetadata: MediaMetadata,
        role: LyricsFetchRole,
        forceRefresh: Boolean = false,
    ) {
        val fetch: suspend () -> RemoteLyricsResult? = {
            fetchMutexFor(mediaMetadata.id).withLock {
                try {
                    // The enabled providers and their signature are pinned once here so the search set, the
                    // all-NotFound decision and the stored signature all use the same snapshot.
                    val selection = ProviderSelection.snapshot(context, lyricsProviders)
                    val existing = database.lyrics(mediaMetadata.id).first()
                    if (!shouldFetchLyrics(existing, selection.signature, System.currentTimeMillis(), forceRefresh)) {
                        return@withLock null
                    }
                    val result = getRemoteLyrics(mediaMetadata, role, selection)
                    val now = System.currentTimeMillis()
                    val entity = lyricsEntityForResult(mediaMetadata.id, result, selection.signature, now, existing)
                    if (entity != null) {
                        withContext(Dispatchers.IO) {
                            database.upsert(entity)
                        }
                        Log.d(
                            TAG,
                            "saved: videoId=${mediaMetadata.id} role=${role.log} " +
                                "provider=${entity.provider ?: "NOT_FOUND"} " +
                                "preferredFailure=${preferredFailureIn(entity.providerSignature)}"
                        )
                    } else {
                        Log.d(TAG, "not saved: videoId=${mediaMetadata.id} role=${role.log} result=${result::class.simpleName}")
                    }
                    result
                } catch (e: CancellationException) {
                    Log.d(TAG, "cancelled: videoId=${mediaMetadata.id} role=${role.log}")
                    throw e
                }
            }
        }
        if (role == LyricsFetchRole.PREFETCH) fetch()
        else fetchCoordinator.fetch(mediaMetadata.id, fetch)
    }

    /**
     * Read the lyric parsing preferences (trim / multiline) shared by all resolution paths
     */
    suspend fun getParserOptions(): LrcUtils.LrcParserOptions {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)
        return LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics")
    }

    suspend fun isLocalPreferred(): Boolean = context.dataStore.get(LyricSourcePrefKey, true)

    /**
     * Run a single provider lookup behind the isolation boundary for the parallel fetch path. A
     * provider that honours the contract returns [LyricsFetchResult]; one that throws instead has its
     * exception normalized to [LyricsFetchResult.Failed]. Cancellation is always re-thrown.
     */
    private suspend fun LyricsProvider.fetchIsolated(
        mediaMetadata: MediaMetadata,
        artistName: String,
    ): LyricsFetchResult =
        try {
            getLyrics(mediaMetadata.id, mediaMetadata.title, artistName, mediaMetadata.duration, mediaMetadata.album?.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsFetchResult.Failed(e)
        }

    /**
     * Lookup lyrics from remote providers.
     *
     * YouTube Music is tried first when enabled. Its timed result wins immediately; a plain result
     * remains available while eligible fallback providers look for actual timed lyrics. On absence
     * or bounded failure, fallback providers can also supply plain lyrics. Each lookup and the whole
     * resolution have time limits.
     *
     * The possible outcomes are: [RemoteLyricsResult.Found] when a usable result was adopted,
     * [RemoteLyricsResult.DefinitiveNotFound] only when every provider reported a definitive absence,
     * [RemoteLyricsResult.Indeterminate] when no usable result was found but at least one provider failed,
     * never reported, or returned an unparseable result, and [RemoteLyricsResult.Skipped] when no
     * provider was enabled.
     */
    private suspend fun getRemoteLyrics(
        mediaMetadata: MediaMetadata,
        role: LyricsFetchRole,
        selection: ProviderSelection,
    ): RemoteLyricsResult {
        val artistName = mediaMetadata.artists
            .filter { it.id != null }
            .joinToString { it.name.removeSuffix(" - Topic") }
            .ifEmpty { mediaMetadata.artists.joinToString { it.name } }
        val start = System.currentTimeMillis()
        Log.d(TAG, "start: videoId=${mediaMetadata.id} role=${role.log} title=\"${mediaMetadata.title}\" artist=\"${artistName}\"")

        lyricsProviders.filterNot { it in selection.providers }.forEach { provider ->
            Log.d(TAG, "${provider.name} SKIPPED (disabled) videoId=${mediaMetadata.id} role=${role.log}")
        }
        val eligible = eligibleLyricsProviders(selection.providers, mediaMetadata.isLocal)
        if (eligible.isEmpty()) {
            Log.d(TAG, "end: skipped videoId=${mediaMetadata.id} role=${role.log} total=0ms (no enabled providers)")
            return RemoteLyricsResult.Skipped
        }

        // errorText = null so adoption sees an unparseable input as null/exception rather than a
        // synthesized UnsyncedLyrics; the user-facing errorText is only used by the display path.
        val verifyOptions = getParserOptions().copy(errorText = null)
        val classifyFound: (String) -> FoundKind = { raw ->
            val parsed = try {
                LrcUtils.parseLyrics(raw, null, verifyOptions, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (parsed) {
                is SemanticLyrics.SyncedLyrics -> FoundKind.SYNCED
                is SemanticLyrics.UnsyncedLyrics -> FoundKind.UNSYNCED
                null -> FoundKind.UNPARSEABLE
            }
        }
        val preferred = eligible.firstOrNull { it.id == YouTubeLyricsProvider.id }
        val enabled = eligible.filterNot { it == preferred }

        suspend fun fallback(): RemoteLyricsResult = coroutineScope {
            if (enabled.isEmpty()) return@coroutineScope RemoteLyricsResult.Skipped
            val channel = Channel<ProviderOutcome>(Channel.UNLIMITED)
            val fetchJobs = enabled.map { provider ->
                launch {
                    val t0 = System.currentTimeMillis()
                    val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        provider.fetchIsolated(mediaMetadata, artistName)
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    val outcome: LyricsFetchResult = when (result) {
                        null -> {
                            Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: timeout after ${PROVIDER_TIMEOUT_MS}ms")
                            LyricsFetchResult.Failed()
                        }

                        is LyricsFetchResult.Found -> {
                            Log.d(TAG, "${provider.name} SUCCESS in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}")
                            result
                        }

                        LyricsFetchResult.NotFound -> {
                            Log.d(TAG, "${provider.name} NOT_FOUND in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}")
                            result
                        }

                        is LyricsFetchResult.Failed -> {
                            Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: ${result.cause?.message}")
                            result
                        }
                    }
                    channel.send(ProviderOutcome(provider.name, outcome))
                }
            }
            // Close the channel once every provider has reported so exhaustion is detected promptly
            // instead of waiting for the overall timeout.
            val closer = launch {
                fetchJobs.joinAll()
                channel.close()
            }

            val aggregator = RemoteLyricsAggregator(enabled.size)
            val deadline = start + OVERALL_TIMEOUT_MS

            try {
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val outcome = withTimeoutOrNull(remaining) {
                        channel.receiveCatching().getOrNull()
                    } ?: break // overall timeout, or every provider has reported
                    // A synced result was adopted: stop and cancel the remaining providers.
                    if (aggregator.offer(outcome.providerName, outcome.result, classifyFound)) break
                }
            } finally {
                fetchJobs.forEach { it.cancel() }
                closer.cancel()
                channel.close()
            }

            val totalMs = System.currentTimeMillis() - start
            val result = aggregator.result()
            when (result) {
                is RemoteLyricsResult.Found ->
                    Log.d(TAG, "adopted: videoId=${mediaMetadata.id} role=${role.log} provider=${result.provider} synced=${result.synced} total=${totalMs}ms")

                RemoteLyricsResult.DefinitiveNotFound ->
                    Log.d(TAG, "end: not found videoId=${mediaMetadata.id} role=${role.log} total=${totalMs}ms")

                RemoteLyricsResult.Indeterminate ->
                    Log.d(TAG, "end: indeterminate videoId=${mediaMetadata.id} role=${role.log} total=${totalMs}ms")

                RemoteLyricsResult.Skipped -> {} // handled above
            }
            result
        }
        return withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
            if (preferred == null) {
                fallback()
            } else {
                resolveWithPreferredProvider(
                    providerName = preferred.name,
                    fetchPreferred = {
                        withTimeoutOrNull(PREFERRED_PROVIDER_TIMEOUT_MS) {
                            preferred.fetchIsolated(mediaMetadata, artistName)
                        } ?: LyricsFetchResult.Failed(java.net.SocketTimeoutException("YouTube lyrics timed out"))
                    },
                    fetchFallback = ::fallback,
                    classifyFound = classifyFound,
                )
            }
        } ?: RemoteLyricsResult.Indeterminate
    }

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        val selection = ProviderSelection.snapshot(context, lyricsProviders)
        val cacheKey = manualLyricsCacheKey(mediaId, songTitle, songArtists, duration, selection.signature)
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }
        searchManualLyrics(selection.providers, mediaId, songTitle, songArtists, duration, callback, ::reportException)
            ?.takeIf { it.isNotEmpty() }?.let { cache.put(cacheKey, it) }
    }

    companion object {
        private const val TAG = "LyricsHelper"
        private const val MAX_CACHE_SIZE = 3

        /** Per-provider timeout for a single remote lookup. */
        private const val PROVIDER_TIMEOUT_MS = 8000L

        /** Two attempts leave time for fallback within the overall lookup deadline. */
        private const val PREFERRED_PROVIDER_TIMEOUT_MS = 3000L

        /** Upper bound for resolving lyrics across all providers of a single song. */
        private const val OVERALL_TIMEOUT_MS = 12000L
    }
}

internal suspend fun searchManualLyrics(
    providers: List<LyricsProvider>,
    mediaId: String,
    songTitle: String,
    songArtists: String,
    duration: Int,
    callback: (LyricsResult) -> Unit,
    onFailure: (Exception) -> Unit,
): List<LyricsResult>? {
    val results = mutableListOf<LyricsResult>()
    var failed = false
    providers.forEach { provider ->
        try {
            provider.getAllLyrics(mediaId, songTitle, songArtists, duration) { lyrics ->
                val result = LyricsResult(provider.name, lyrics)
                results += result
                callback(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            onFailure(e)
        }
    }
    return if (failed) null else results
}

internal data class ManualLyricsCacheKey(
    val mediaId: String,
    val songTitle: String,
    val songArtists: String,
    val duration: Int,
    val providerSignature: String,
)

internal fun manualLyricsCacheKey(
    mediaId: String,
    songTitle: String,
    songArtists: String,
    duration: Int,
    providerSignature: String,
): ManualLyricsCacheKey = ManualLyricsCacheKey(mediaId, songTitle, songArtists, duration, providerSignature)

/** Time a negative cache (LYRICS_NOT_FOUND) is trusted before a fresh remote fetch is attempted. */
const val NEGATIVE_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
internal const val PREFERRED_FAILURE_RETRY_MS = 15L * 60 * 1000
private const val RATE_LIMIT_RETRY_MS = 60L * 60 * 1000
private const val PREFERRED_FAILURE_MARKER = "|preferred-failure="

private fun preferredFailureIn(storedSignature: String?): LyricsFailureKind? {
    val suffix = storedSignature?.substringAfterLast(PREFERRED_FAILURE_MARKER, missingDelimiterValue = "")
    return LyricsFailureKind.values().firstOrNull { it.name == suffix }
}

/** Store an adopted fallback's preferred-source failure without changing the Room schema. */
internal fun lyricsEntityForResult(
    id: String,
    result: RemoteLyricsResult,
    signature: String,
    now: Long,
    existing: LyricsEntity? = null,
): LyricsEntity? = when (result) {
    is RemoteLyricsResult.Found -> LyricsEntity(
        id = id,
        lyrics = result.raw,
        provider = result.provider,
        lastCheckedAt = now,
        providerSignature = if (result.preferredFailure == null) signature
            else "$signature$PREFERRED_FAILURE_MARKER${result.preferredFailure.name}",
    )

    RemoteLyricsResult.DefinitiveNotFound ->
        if (existing != null && existing.lyrics != LYRICS_NOT_FOUND && preferredFailureIn(existing.providerSignature) != null) {
            existing.copy(lastCheckedAt = now, providerSignature = signature)
        } else {
            LyricsEntity(id, LYRICS_NOT_FOUND, lastCheckedAt = now, providerSignature = signature)
        }

    RemoteLyricsResult.Indeterminate ->
        if (existing != null && preferredFailureIn(existing.providerSignature) != null) {
            existing.copy(lastCheckedAt = now)
        } else null

    RemoteLyricsResult.Skipped -> null
}

/**
 * Pure decision for whether a remote fetch should run for one song.
 *
 * A positive cache (real lyrics) is always kept. A negative cache is re-fetched when it is stale past
 * [ttlMs], was written under a different provider [signature], predates the signature columns (null
 * fields), or the device clock moved backwards ([now] earlier than the stored timestamp). [forceRefresh]
 * and a missing row always fetch.
 */
internal fun shouldFetchLyrics(
    entity: LyricsEntity?,
    signature: String,
    now: Long,
    forceRefresh: Boolean,
    ttlMs: Long = NEGATIVE_CACHE_TTL_MS,
): Boolean {
    if (forceRefresh) return true
    if (entity == null) return true
    if (entity.lyrics != LYRICS_NOT_FOUND) {
        val failure = preferredFailureIn(entity.providerSignature) ?: return false
        val storedSignature = entity.providerSignature?.substringBeforeLast(PREFERRED_FAILURE_MARKER) ?: return true
        val lastChecked = entity.lastCheckedAt ?: return true
        if (storedSignature != signature || now < lastChecked) return true
        val retryMs = if (failure == LyricsFailureKind.RATE_LIMIT) RATE_LIMIT_RETRY_MS else PREFERRED_FAILURE_RETRY_MS
        return now - lastChecked >= retryMs
    }
    val lastChecked = entity.lastCheckedAt ?: return true
    val storedSignature = entity.providerSignature ?: return true
    if (storedSignature != signature) return true
    if (now < lastChecked) return true
    return now - lastChecked >= ttlMs
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

/** A local file path is not a YouTube video ID, so ID-only services cannot match it safely. */
internal fun eligibleLyricsProviders(providers: List<LyricsProvider>, isLocal: Boolean): List<LyricsProvider> =
    if (isLocal) providers.filterNot { it.id in setOf("youtube", "youtube-subtitle", "simpmusic") }
    else providers

/**
 * Outcome reported by a single provider during parallel resolution.
 */
private data class ProviderOutcome(
    val providerName: String,
    val result: LyricsFetchResult,
)

/**
 * Snapshot of the enabled providers taken once at the start of a fetch, together with a signature
 * derived from their stable ids (sorted, so provider order never affects it). The same snapshot drives
 * the search set, the all-NotFound decision and the stored signature, so a provider-configuration
 * change during a fetch cannot desync them.
 */
data class ProviderSelection(
    val providers: List<LyricsProvider>,
    val signature: String,
) {
    companion object {
        fun snapshot(context: Context, all: List<LyricsProvider>): ProviderSelection {
            val enabled = all.filter { it.isEnabled(context) }
            val signature = enabled.map { it.id }.sorted().joinToString(",")
            return ProviderSelection(enabled, signature)
        }
    }
}

/**
 * Aggregate outcome of resolving lyrics across every enabled provider for one song.
 */
sealed interface RemoteLyricsResult {
    data class Found(
        val provider: String,
        val raw: String,
        val synced: Boolean,
        val preferredFailure: LyricsFailureKind? = null,
    ) : RemoteLyricsResult
    data object DefinitiveNotFound : RemoteLyricsResult
    data object Indeterminate : RemoteLyricsResult
    data object Skipped : RemoteLyricsResult
}

/** How a [LyricsFetchResult.Found] parses when judged for adoption. */
enum class FoundKind { SYNCED, UNSYNCED, UNPARSEABLE }

/**
 * Accumulates provider outcomes and derives the aggregate [RemoteLyricsResult]. The rules, independent
 * of the concurrency around it: the first synced Found wins; an unsynced Found is held as a fallback; a
 * DefinitiveNotFound is reported only when every enabled provider reported a definitive NotFound (a
 * Failed, an unparseable Found, or a provider that never reported all block it, yielding Indeterminate).
 *
 * @param enabledCount number of providers that were expected to report
 */
class RemoteLyricsAggregator(private val enabledCount: Int) {
    private var adoptedSynced: RemoteLyricsResult.Found? = null
    private var heldUnsynced: RemoteLyricsResult.Found? = null
    private var notFoundCount = 0
    private var nonNotFoundCount = 0

    /**
     * Fold one provider outcome into the running result.
     *
     * @return true once a synced result has been adopted, signalling that no further outcomes are needed.
     */
    fun offer(providerName: String, result: LyricsFetchResult, classifyFound: (String) -> FoundKind): Boolean {
        when (result) {
            is LyricsFetchResult.Found -> when (classifyFound(result.raw)) {
                FoundKind.SYNCED -> {
                    if (adoptedSynced == null) {
                        adoptedSynced = RemoteLyricsResult.Found(providerName, result.raw, synced = true)
                    }
                    return true
                }

                FoundKind.UNSYNCED -> if (heldUnsynced == null) {
                    heldUnsynced = RemoteLyricsResult.Found(providerName, result.raw, synced = false)
                }

                FoundKind.UNPARSEABLE -> nonNotFoundCount++ // not a usable result, but not an absence either
            }

            LyricsFetchResult.NotFound -> notFoundCount++
            is LyricsFetchResult.Failed -> nonNotFoundCount++
        }
        return false
    }

    fun result(): RemoteLyricsResult = when {
        adoptedSynced != null -> adoptedSynced as RemoteLyricsResult.Found
        heldUnsynced != null -> heldUnsynced as RemoteLyricsResult.Found
        nonNotFoundCount == 0 && notFoundCount == enabledCount -> RemoteLyricsResult.DefinitiveNotFound
        else -> RemoteLyricsResult.Indeterminate
    }
}
