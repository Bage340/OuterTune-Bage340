package com.dd3boh.outertune.transfer

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

internal object M3uTransfer {
    private const val PLAYLIST_TAG = "#OUTERTUNE-PLAYLIST:"
    private const val TRACK_TAG = "#OUTERTUNE-TRACK:"
    private const val MAX_LINES = TransferLimits.MAX_TRACKS * 4 + 1024
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    fun write(document: TransferDocument): String {
        if (document.library.isNotEmpty() || document.playlists.size != 1) TransferValidation.fail("M3U8 requires exactly one playlist and no separate library")
        val playlist = document.playlists.single()
        val content = buildString {
            append("#EXTM3U\n")
            append(PLAYLIST_TAG)
            append(buildJsonObject {
                put("stableId", JsonPrimitive(playlist.stableId))
                put("title", JsonPrimitive(playlist.title))
                put("isLocal", JsonPrimitive(playlist.isLocal))
                put("browseId", playlist.browseId?.let(::JsonPrimitive) ?: JsonNull)
                put("bookmarked", JsonPrimitive(playlist.bookmarked))
            })
            append('\n')
            playlist.tracks.forEach { track ->
                append(TRACK_TAG)
                append(JsonTransfer.writeTrack(track))
                append('\n')
                append("#EXTINF:")
                append(track.durationSeconds ?: -1)
                append(',')
                append(track.artists.joinToString("; ").replace('\n', ' ').replace('\r', ' '))
                if (track.artists.isNotEmpty()) append(" - ")
                append(track.title.replace('\n', ' ').replace('\r', ' '))
                append('\n')
                append(if (track.source == TrackSource.YOUTUBE) "https://www.youtube.com/watch?v=${track.stableId}"
                    else track.localUri ?: TransferValidation.fail("M3U cannot represent a local track without a path"))
                append('\n')
            }
        }
        if (content.lineSequence().any { it.length > TransferLimits.MAX_LINE_CHARS }) TransferValidation.fail("M3U line exceeds limit")
        return content
    }

    fun read(content: String): TransferDocument {
        val lines = content.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trimEnd('\r') != "#EXTM3U") TransferValidation.fail("Missing M3U header")
        val tracks = ArrayList<TransferTrack>()
        var playlistId = "m3u-import"
        var playlistTitle = "Imported playlist"
        var playlistIsLocal = true
        var playlistBrowseId: String? = null
        var playlistBookmarked = false
        var pendingTrack: TransferTrack? = null
        var pendingInf: Pair<Int?, String>? = null
        var lineCount = 1
        while (lines.hasNext()) {
            if (++lineCount > MAX_LINES) TransferValidation.fail("Too many M3U lines")
            val raw = lines.next()
            val line = raw.trimEnd('\r')
            if (line.length > TransferLimits.MAX_LINE_CHARS) TransferValidation.fail("M3U line exceeds limit")
            if (line.isEmpty()) continue
            when {
                line.startsWith(PLAYLIST_TAG) -> {
                    if (tracks.isNotEmpty() || pendingTrack != null || pendingInf != null) TransferValidation.fail("Late playlist metadata")
                    val objectValue = JsonTransfer.parseObject(line.substring(PLAYLIST_TAG.length))
                    playlistId = (objectValue["stableId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: TransferValidation.fail("Invalid playlist ID")
                    playlistTitle = (objectValue["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: TransferValidation.fail("Invalid playlist title")
                    playlistIsLocal = when (objectValue["isLocal"]?.toString()) {
                        null -> true
                        "true" -> true
                        "false" -> false
                        else -> TransferValidation.fail("Invalid playlist source")
                    }
                    playlistBrowseId = objectValue["browseId"]?.takeUnless { it == JsonNull }?.let {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                            ?: TransferValidation.fail("Invalid playlist browse ID")
                    }
                    playlistBookmarked = when (objectValue["bookmarked"]?.toString()) {
                        null -> false
                        "true" -> true
                        "false" -> false
                        else -> TransferValidation.fail("Invalid playlist bookmark")
                    }
                }
                line.startsWith(TRACK_TAG) -> {
                    if (pendingTrack != null || pendingInf != null) TransferValidation.fail("Incomplete M3U entry")
                    pendingTrack = JsonTransfer.readTrack(JsonTransfer.parseObject(line.substring(TRACK_TAG.length)))
                }
                line.startsWith("#EXTINF:") -> {
                    if (pendingInf != null) TransferValidation.fail("Incomplete M3U entry")
                    val value = line.substringAfter(':')
                    val comma = value.indexOf(',')
                    if (comma < 0) TransferValidation.fail("Invalid EXTINF")
                    val seconds = value.substring(0, comma).substringBefore(' ').toIntOrNull() ?: TransferValidation.fail("Invalid EXTINF duration")
                    pendingInf = (if (seconds < 0) null else seconds) to value.substring(comma + 1)
                }
                line.startsWith("#PLAYLIST:") -> {
                    if (tracks.isEmpty()) playlistTitle = line.substringAfter(':')
                }
                line.startsWith('#') -> Unit
                else -> {
                    val locator = identify(line)
                    val track = pendingTrack?.also {
                        if (it.source != locator.first || (it.source == TrackSource.YOUTUBE && it.stableId != locator.second) ||
                            (it.source == TrackSource.LOCAL && it.localUri != locator.third)) TransferValidation.fail("M3U metadata does not match locator")
                    } ?: makeTrack(locator, pendingInf)
                    tracks.add(track)
                    if (tracks.size > TransferLimits.MAX_TRACKS) TransferValidation.fail("Too many M3U tracks")
                    pendingTrack = null
                    pendingInf = null
                }
            }
        }
        if (pendingTrack != null || pendingInf != null) TransferValidation.fail("Incomplete M3U entry")
        return TransferDocument(emptyList(), listOf(TransferPlaylist(playlistId, playlistTitle, tracks,
            isLocal = playlistIsLocal, browseId = playlistBrowseId, bookmarked = playlistBookmarked)))
    }

    private fun makeTrack(locator: Triple<TrackSource, String, String?>, info: Pair<Int?, String>?): TransferTrack {
        val display = info?.second.orEmpty()
        val separator = display.indexOf(" - ")
        val artists = if (separator > 0) display.substring(0, separator).split("; ") else emptyList()
        val title = if (separator > 0) display.substring(separator + 3) else display.ifBlank { locator.second }
        return TransferTrack(locator.first, locator.second, title, artists, durationSeconds = info?.first, localUri = locator.third)
    }

    private fun identify(line: String): Triple<TrackSource, String, String?> {
        val legacy = Regex("^([A-Za-z0-9_-]{1,64}), (.+)$").matchEntire(line)
        if (legacy != null && TransferValidation.safeLocalReference(legacy.groupValues[2])) {
            return Triple(TrackSource.LOCAL, legacy.groupValues[1], legacy.groupValues[2])
        }
        if (TransferValidation.safeLocalReference(line)) return Triple(TrackSource.LOCAL, line, line)
        val uri = try { URI(line) } catch (error: Exception) { throw TransferException("Invalid M3U locator", error) }
        if (!uri.isAbsolute && !line.startsWith("//") && !line.startsWith('/') &&
            !line.replace('\\', '/').split('/').any { it == ".." } &&
            uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null &&
            !line.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return Triple(TrackSource.LOCAL, line, null)
        }
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null || uri.fragment != null) TransferValidation.fail("Unsupported M3U locator")
        val host = uri.host?.lowercase() ?: TransferValidation.fail("Unsupported M3U host")
        val id = when (host) {
            "youtu.be", "www.youtu.be" -> uri.path?.removePrefix("/")
            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery?.split('&')?.mapNotNull { item ->
                    val parts = item.split('=', limit = 2)
                    if (parts[0] == "v" && parts.size == 2) URLDecoder.decode(parts[1], "UTF-8") else null
                }?.singleOrNull()
                uri.path?.startsWith("/shorts/") == true -> uri.path?.removePrefix("/shorts/")
                else -> null
            }
            else -> null
        }
        if (id == null || !videoId.matches(id)) TransferValidation.fail("Unsupported YouTube URL")
        return Triple(TrackSource.YOUTUBE, id, null)
    }
}
