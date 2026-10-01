package com.dd3boh.outertune.transfer

import android.content.Context
import android.net.Uri
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime

fun interface LocalReferenceAccess {
    fun isAccessible(reference: String): Boolean
}

fun interface RemoteTrackMetadataResolver {
    suspend fun resolve(videoId: String): TransferTrack?
}

private val youtubeTrackMetadataResolver = RemoteTrackMetadataResolver { videoId ->
    YouTube.queue(listOf(videoId)).getOrNull()?.singleOrNull { it.id == videoId }?.let { song ->
        TransferTrack(
            source = TrackSource.YOUTUBE,
            stableId = videoId,
            title = song.title,
            artists = song.artists.map { it.name },
            album = song.album?.name,
            durationSeconds = song.duration,
        )
    }
}

class AndroidLocalReferenceAccess(context: Context) : LocalReferenceAccess {
    private val resolver = context.applicationContext.contentResolver

    override fun isAccessible(reference: String): Boolean {
        if (!TransferValidation.safeLocalReference(reference)) return false
        return runCatching {
            if (reference.startsWith("content://")) {
                resolver.openAssetFileDescriptor(Uri.parse(reference), "r")?.use { true } ?: false
            } else {
                val path = if (reference.startsWith("file://")) Uri.parse(reference).path ?: return false else reference
                File(path).let { it.isFile && it.canRead() }
            }
        }.getOrDefault(false)
    }
}

data class TransferImportPreview(val document: TransferDocument, val staged: StagedTransfer)

data class TransferImportResult(
    val createdSongs: Int,
    val reusedSongs: Int,
    val createdPlaylists: Int,
    val reusedPlaylists: Int,
    val addedPlaylistEntries: Int,
    val skippedUnresolved: Int,
    val skippedDuplicateEntries: Int,
)

class TransferRepository(
    private val database: MusicDatabase,
    private val localAccess: LocalReferenceAccess,
    private val remoteMetadata: RemoteTrackMetadataResolver = youtubeTrackMetadataResolver,
) {
    suspend fun export(
        format: TransferFormat,
        playlistIds: List<String>? = null,
        includeLibrary: Boolean = true,
    ): ByteArray = withContext(Dispatchers.IO) {
        val document = database.withTransferTransaction {
            val playlistRows = playlistEntities().associateBy { it.id }
            val selected = (playlistIds?.distinct() ?: playlistRows.values
                .filter { it.isLocal || it.bookmarkedAt != null }
                .map { it.id }.sorted()).map { id ->
                playlistRows[id] ?: TransferValidation.fail("Playlist not found: $id")
            }
            val librarySongs = if (includeLibrary) transferLibrarySongs() else emptyList()
            val playlistSongs = selected.map { it to transferPlaylistSongs(it.id) }
            val artistNames = (librarySongs.asSequence().map { it.id } +
                playlistSongs.asSequence().flatMap { (_, songs) -> songs.asSequence().map { it.song.id } })
                .distinct().toList().chunked(400)
                .flatMap { transferArtistNames(it) }
                .groupBy({ it.songId }, { it.name })
            TransferDocument(
                library = librarySongs.map { toTransferTrack(it, artistNames[it.id].orEmpty()) },
                playlists = playlistSongs.map { (playlist, songs) ->
                    TransferPlaylist(
                        stableId = playlist.id,
                        title = playlist.name,
                        tracks = songs.map { toTransferTrack(it.song, artistNames[it.song.id].orEmpty()) },
                        isLocal = playlist.isLocal,
                        browseId = playlist.browseId,
                        bookmarked = playlist.bookmarkedAt != null,
                    )
                },
            )
        }
        TransferCodec.encode(format, document)
    }

    suspend fun prepareImport(format: TransferFormat, bytes: ByteArray): TransferImportPreview = withContext(Dispatchers.IO) {
        val decoded = TransferCodec.decode(format, bytes)
        val existing = database.withTransferTransaction { inventory(decoded).identities }
        val document = if (format == TransferFormat.M3U8) enrichBareYouTubeTracks(decoded, existing) else decoded
        val accessible = accessibleReferences(document)
        TransferImportPreview(document, TransferStaging.stage(document, existing, accessible))
    }

    suspend fun commitImport(preview: TransferImportPreview): TransferImportResult = withContext(Dispatchers.IO) {
        TransferValidation.validate(preview.document)
        val accessible = accessibleReferences(preview.document)
        database.withTransferTransaction {
            val inventory = inventory(preview.document)
            val staged = TransferStaging.stage(preview.document, inventory.identities, accessible)
            applyImport(staged, inventory)
        }
    }

    suspend fun import(format: TransferFormat, bytes: ByteArray): TransferImportResult =
        commitImport(prepareImport(format, bytes))

    private fun accessibleReferences(document: TransferDocument): Set<String> =
        (document.library.asSequence() + document.playlists.asSequence().flatMap { it.tracks.asSequence() })
            .filter { it.source == TrackSource.LOCAL }
            .mapNotNull { it.localUri }
            .distinct()
            .filter { localAccess.isAccessible(it) }
            .toSet()

    private suspend fun enrichBareYouTubeTracks(document: TransferDocument, existing: Set<TrackIdentity>): TransferDocument {
        val placeholders = document.playlists.asSequence().flatMap { it.tracks.asSequence() }
            .filter { it.source == TrackSource.YOUTUBE && it.title == it.stableId && it.artists.isEmpty() &&
                it.album == null && it.durationSeconds == null && TrackIdentity(TrackSource.YOUTUBE, it.stableId) !in existing }
            .map { it.stableId }.distinct().toList()
        if (placeholders.isEmpty()) return document
        val resolved = HashMap<String, TransferTrack>()
        withTimeoutOrNull(20_000) {
            for (id in placeholders) {
                val metadata = try {
                    withTimeoutOrNull(2_500) { remoteMetadata.resolve(id) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (metadata != null && metadata.source == TrackSource.YOUTUBE && metadata.stableId == id) {
                    val enriched = TransferTrack(TrackSource.YOUTUBE, id, metadata.title, metadata.artists,
                        metadata.album, metadata.durationSeconds)
                    if (runCatching { TransferValidation.validate(TransferDocument(listOf(enriched), emptyList())) }.isSuccess) {
                        resolved[id] = enriched
                    }
                }
            }
        }
        if (resolved.isEmpty()) return document
        return document.copy(playlists = document.playlists.map { playlist ->
            playlist.copy(tracks = playlist.tracks.map { track ->
                resolved[track.stableId]?.takeIf { track.source == TrackSource.YOUTUBE && track.title == track.stableId &&
                    track.artists.isEmpty() && track.album == null && track.durationSeconds == null }
                    ?.let { metadata -> track.copy(title = metadata.title, artists = metadata.artists,
                        album = metadata.album, durationSeconds = metadata.durationSeconds) } ?: track
            })
        })
    }

    private data class Inventory(
        val byId: MutableMap<String, SongEntity>,
        val byLocalPath: MutableMap<String, SongEntity>,
        val identities: Set<TrackIdentity>,
    )

    private fun MusicDatabase.inventory(document: TransferDocument): Inventory {
        val tracks = document.library.asSequence() + document.playlists.asSequence().flatMap { it.tracks.asSequence() }
        val snapshot = tracks.toList()
        val byId = snapshot.map { it.stableId }.distinct().chunked(400)
            .flatMap(::transferSongEntities).associateByTo(LinkedHashMap()) { it.id }
        val localTracks = snapshot.filter { it.source == TrackSource.LOCAL && it.localUri != null }
        val byLocalPath = LinkedHashMap<String, SongEntity>()
        if (localTracks.isNotEmpty()) {
            val storedByPath = transferLocalIdentities().groupBy { localReferenceKey(it.localPath) }
            val matchingIds = localTracks.mapNotNull { track ->
                val candidates = storedByPath[localReferenceKey(track.localUri!!)].orEmpty()
                if (candidates.size > 1) TransferValidation.fail("Ambiguous local reference")
                candidates.singleOrNull()?.id
            }.distinct()
            val matchingRows = matchingIds.chunked(400).flatMap(::transferSongEntities).associateBy { it.id }
            localTracks.forEach { track ->
                val stored = storedByPath[localReferenceKey(track.localUri!!)]?.singleOrNull() ?: return@forEach
                val matched = matchingRows[stored.id] ?: TransferValidation.fail("Missing local song")
                val idMatch = byId[track.stableId]
                if (idMatch != null && idMatch.id != matched.id) TransferValidation.fail("Local ID conflicts with path")
                byLocalPath[track.localUri] = matched
                byId[matched.id] = matched
            }
        }
        val identities = HashSet<TrackIdentity>()
        byId.values.filterNot { it.isLocal }.forEach { identities.add(TrackIdentity(TrackSource.YOUTUBE, it.id)) }
        byLocalPath.keys.forEach { reference -> identities.add(TrackIdentity(TrackSource.LOCAL, localReferenceKey(reference) ?: reference)) }
        return Inventory(byId, byLocalPath, identities)
    }

    private suspend fun MusicDatabase.applyImport(staged: StagedTransfer, inventory: Inventory): TransferImportResult {
        val context = currentCoroutineContext()
        val now = LocalDateTime.now()
        val resolved = HashMap<TrackIdentity, SongEntity>()
        val artistIdsByName = HashMap<String, String>()
        val reused = HashSet<TrackIdentity>()
        var createdSongs = 0
        val all = staged.library.asSequence() + staged.playlists.asSequence().flatMap { it.tracks.asSequence() }
        all.forEach { stagedTrack ->
            context.ensureActive()
            if (stagedTrack.disposition == TransferDisposition.UNRESOLVED) return@forEach
            val track = stagedTrack.track
            val identity = portableIdentity(track)
            var song = resolved[identity] ?: if (track.source == TrackSource.LOCAL) inventory.byLocalPath[track.localUri]
                else inventory.byId[track.stableId]?.also { if (it.isLocal) TransferValidation.fail("YouTube ID collides with local song") }
            if (song == null) {
                song = insertNewSong(track, now, inventory.byId)
                track.artists.forEachIndexed { index, name ->
                    context.ensureActive()
                    val artistId = artistIdsByName.getOrPut(name) {
                        resolveArtistId(name, track.source == TrackSource.LOCAL)
                    }
                    insert(SongArtistMap(songId = song.id, artistId = artistId, position = index))
                }
                createdSongs++
            } else if (identity !in resolved) {
                reused.add(identity)
            }
            val merged = song.copy(
                liked = song.liked || track.liked,
                likedDate = song.likedDate ?: if (track.liked) now else null,
                inLibrary = song.inLibrary ?: if (track.inLibrary) now else null,
            )
            if (merged != song) update(merged)
            resolved[identity] = merged
            inventory.byId[merged.id] = merged
            if (track.source == TrackSource.LOCAL && track.localUri != null) inventory.byLocalPath[track.localUri] = merged
        }

        var createdPlaylists = 0
        var reusedPlaylists = 0
        var addedEntries = 0
        var skippedDuplicateEntries = 0
        val playlistRows = playlistEntities().associateBy { it.id }
        staged.playlists.forEach { stagedPlaylist ->
            context.ensureActive()
            val playlist = stagedPlaylist.playlist
            val existing = playlistRows[playlist.stableId]
            if (existing != null && !existing.isLocal) {
                if (playlist.isLocal || existing.browseId != playlist.browseId) {
                    TransferValidation.fail("Playlist ID conflicts with a different playlist")
                }
                // The account-linked row already existed before this untrusted import.
                // Never change its metadata or membership based on a file.
                reusedPlaylists++
                return@forEach
            }
            if (existing != null && existing.name != playlist.title)
                TransferValidation.fail("Playlist ID conflicts with a different playlist")
            if (existing == null) {
                insert(PlaylistEntity(id = playlist.stableId, name = playlist.title,
                    // A file cannot prove ownership of browseId. Keep remote playlist
                    // exports as editable, offline snapshots with no account authority.
                    isLocal = true, browseId = null,
                    isEditable = true,
                    bookmarkedAt = if (playlist.bookmarked) now else null))
                createdPlaylists++
            } else reusedPlaylists++
            val maps = songMapsToPlaylist(playlist.stableId, 0)
            val membership = maps.mapTo(HashSet()) { it.songId }
            var nextPosition = (maps.maxOfOrNull { it.position } ?: -1).toLong() + 1
            stagedPlaylist.tracks.forEach { stagedTrack ->
                context.ensureActive()
                if (stagedTrack.disposition == TransferDisposition.UNRESOLVED) return@forEach
                val song = resolved[portableIdentity(stagedTrack.track)] ?: TransferValidation.fail("Missing staged song")
                if (!membership.add(song.id)) {
                    skippedDuplicateEntries++
                    return@forEach
                }
                if (nextPosition > Int.MAX_VALUE) TransferValidation.fail("Playlist position overflow")
                insert(PlaylistSongMap(playlistId = playlist.stableId, songId = song.id, position = nextPosition.toInt()))
                nextPosition++
                addedEntries++
            }
        }
        context.ensureActive()
        return TransferImportResult(
            createdSongs = createdSongs,
            reusedSongs = reused.size,
            createdPlaylists = createdPlaylists,
            reusedPlaylists = reusedPlaylists,
            addedPlaylistEntries = addedEntries,
            skippedUnresolved = staged.unresolvedCount,
            skippedDuplicateEntries = skippedDuplicateEntries,
        )
    }

    private fun MusicDatabase.insertNewSong(track: TransferTrack, now: LocalDateTime, byId: Map<String, SongEntity>): SongEntity {
        if (track.source == TrackSource.YOUTUBE) {
            val song = createSong(track, now, track.stableId)
            if (insert(song) == -1L) TransferValidation.fail("YouTube song ID already exists")
            return song
        }
        var id = track.stableId
        repeat(16) {
            if (id !in byId) {
                val song = createSong(track, now, id)
                if (insert(song) != -1L) return song
            }
            id = SongEntity.generateSongId()
        }
        TransferValidation.fail("Could not allocate local song ID")
    }

    private fun MusicDatabase.resolveArtistId(name: String, isLocal: Boolean): String {
        artistByName(name)?.let { return it.id }
        repeat(16) {
            val id = ArtistEntity.generateArtistId()
            if (artistById(id) != null) return@repeat
            insert(ArtistEntity(id = id, name = name, isLocal = isLocal))
            if (artistById(id)?.name == name) return id
        }
        TransferValidation.fail("Could not allocate artist ID")
    }

    private fun createSong(track: TransferTrack, now: LocalDateTime, id: String): SongEntity = SongEntity(
            id = id, title = track.title, duration = track.durationSeconds ?: -1,
            localPath = track.localUri, isLocal = track.source == TrackSource.LOCAL,
            liked = track.liked, likedDate = if (track.liked) now else null,
            inLibrary = if (track.inLibrary) now else null,
            albumName = track.album,
        )

    private fun portableIdentity(track: TransferTrack): TrackIdentity = TrackIdentity(
        track.source, if (track.source == TrackSource.LOCAL) track.localUri?.let { localReferenceKey(it) ?: it } ?: track.stableId else track.stableId,
    )

    private fun toTransferTrack(song: Song, artistNames: List<String>): TransferTrack = TransferTrack(
        source = if (song.song.isLocal) TrackSource.LOCAL else TrackSource.YOUTUBE,
        stableId = song.song.id,
        title = song.song.title,
        artists = artistNames,
        album = song.song.albumName ?: song.album?.title,
        durationSeconds = song.song.duration.takeIf { it >= 0 },
        localUri = song.song.localPath.takeIf { song.song.isLocal },
        liked = song.song.liked,
        inLibrary = song.song.inLibrary != null,
    )
}
