package com.dd3boh.outertune.transfer

import java.net.URI

enum class TrackSource { YOUTUBE, LOCAL }

data class TransferTrack(
    val source: TrackSource,
    val stableId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationSeconds: Int? = null,
    val localUri: String? = null,
    val liked: Boolean = false,
    val inLibrary: Boolean = false,
)

data class TransferPlaylist(
    val stableId: String,
    val title: String,
    val tracks: List<TransferTrack>,
    val isLocal: Boolean = true,
    val browseId: String? = null,
    val bookmarked: Boolean = false,
)

data class TransferDocument(val library: List<TransferTrack>, val playlists: List<TransferPlaylist>)

enum class TransferFormat { JSON, CSV, M3U8 }

class TransferException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object TransferLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_TRACKS = 10_000
    const val MAX_PLAYLISTS = 500
    const val MAX_FIELD_CHARS = 4096
    const val MAX_LINE_CHARS = 16_384
}

data class TrackIdentity(val source: TrackSource, val stableId: String)

enum class TransferDisposition { EXISTING, CREATE, DUPLICATE, UNRESOLVED }

data class StagedTrack(val track: TransferTrack, val disposition: TransferDisposition)

data class StagedPlaylist(val playlist: TransferPlaylist, val tracks: List<StagedTrack>)

data class StagedTransfer(val library: List<StagedTrack>, val playlists: List<StagedPlaylist>) {
    val unresolvedCount: Int get() = library.count { it.disposition == TransferDisposition.UNRESOLVED } +
        playlists.sumOf { playlist -> playlist.tracks.count { it.disposition == TransferDisposition.UNRESOLVED } }
    val duplicateCount: Int get() = library.count { it.disposition == TransferDisposition.DUPLICATE } +
        playlists.sumOf { playlist -> playlist.tracks.count { it.disposition == TransferDisposition.DUPLICATE } }
}

object TransferStaging {
    fun stage(
        document: TransferDocument,
        existingTracks: Set<TrackIdentity>,
        accessibleLocalUris: Set<String>,
    ): StagedTransfer {
        TransferValidation.validate(document)
        val newlyCreated = HashSet<TrackIdentity>()
        fun classify(track: TransferTrack): StagedTrack {
            val identity = TrackIdentity(track.source, track.stableId)
            val localIdentity = track.localUri?.let { TrackIdentity(TrackSource.LOCAL, it) }
            val portableIdentity = if (track.source == TrackSource.LOCAL) localIdentity ?: identity else identity
            val disposition = when {
                track.source == TrackSource.LOCAL && (track.localUri == null || track.localUri !in accessibleLocalUris) -> TransferDisposition.UNRESOLVED
                track.source == TrackSource.LOCAL && localIdentity != null && localIdentity in existingTracks -> TransferDisposition.EXISTING
                track.source == TrackSource.YOUTUBE && identity in existingTracks -> TransferDisposition.EXISTING
                portableIdentity in newlyCreated -> TransferDisposition.DUPLICATE
                else -> TransferDisposition.CREATE
            }
            if (disposition == TransferDisposition.CREATE) newlyCreated.add(portableIdentity)
            return StagedTrack(track, disposition)
        }
        return StagedTransfer(
            document.library.map(::classify),
            document.playlists.map { StagedPlaylist(it, it.tracks.map(::classify)) },
        )
    }
}

internal object TransferValidation {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    fun validate(document: TransferDocument) {
        if (document.playlists.size > TransferLimits.MAX_PLAYLISTS) fail("Too many playlists")
        if (document.library.size.toLong() + document.playlists.sumOf { it.tracks.size.toLong() } > TransferLimits.MAX_TRACKS) fail("Too many tracks")
        document.library.forEach(::validate)
        val playlistIds = HashSet<String>()
        document.playlists.forEach { playlist ->
            identifier(playlist.stableId, "Playlist ID")
            field(playlist.title, "Playlist title", allowEmpty = false)
            playlist.browseId?.let { identifier(it, "Playlist browse ID") }
            if (playlist.isLocal == (playlist.browseId != null)) fail("Invalid playlist source")
            if (!playlistIds.add(playlist.stableId)) fail("Duplicate playlist ID")
            playlist.tracks.forEach(::validate)
        }
    }

    private fun validate(track: TransferTrack) {
        identifier(track.stableId, "Track ID")
        field(track.title, "Track title", allowEmpty = false)
        if (track.artists.size > 32) fail("Too many artists")
        track.artists.forEach { field(it, "Artist", allowEmpty = false) }
        track.album?.let { field(it, "Album") }
        track.localUri?.let { identifier(it, "Local URI") }
        if (track.durationSeconds != null && track.durationSeconds !in 0..86400) fail("Invalid duration")
        if (track.source == TrackSource.YOUTUBE && (!videoId.matches(track.stableId) || track.localUri != null)) fail("Invalid YouTube track")
        if (track.source == TrackSource.LOCAL && track.localUri != null && !safeLocalReference(track.localUri)) fail("Unsafe local reference")
    }

    fun field(value: String, name: String, allowEmpty: Boolean = true) {
        if (value.length > TransferLimits.MAX_FIELD_CHARS || (!allowEmpty && value.isBlank()) || value.any { it == '\u0000' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }) fail("Invalid $name")
    }

    private fun identifier(value: String, name: String) {
        field(value, name, allowEmpty = false)
        if (value.any { it == '\n' || it == '\r' || it == '\t' }) fail("Invalid $name")
    }

    fun safeLocalReference(value: String): Boolean {
        if (value.replace('\\', '/').split('/').any { it == ".." }) return false
        if (value.startsWith("content://") || value.startsWith("file://")) {
            val uri = try { URI(value) } catch (_: Exception) { return false }
            if (uri.path?.split('/')?.any { it == ".." } == true || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null) return false
            if (uri.scheme == "content") return !uri.authority.isNullOrBlank()
            return uri.scheme == "file" && (uri.authority.isNullOrBlank() || uri.authority == "localhost") && !uri.path.isNullOrBlank()
        }
        if (value.startsWith("/") && !value.startsWith("//")) return true
        return Regex("[A-Za-z]:[\\\\/].+").matches(value)
    }

    fun fail(message: String): Nothing = throw TransferException(message)
}
