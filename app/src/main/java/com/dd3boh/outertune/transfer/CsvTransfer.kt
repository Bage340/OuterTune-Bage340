package com.dd3boh.outertune.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object CsvTransfer {
    private val legacyColumns = listOf(
        "schemaVersion", "kind", "playlistId", "playlistTitle", "source", "stableId", "title",
        "artists", "album", "durationSeconds", "localUri", "liked", "inLibrary",
    )
    private val columns = legacyColumns + listOf("playlistIsLocal", "playlistBrowseId", "playlistBookmarked")

    fun write(document: TransferDocument): String = buildString {
        appendRow(columns)
        document.library.forEach { appendRow(trackRow("library", "", "", it)) }
        document.playlists.forEach { playlist ->
            appendRow(listOf("1", "playlist", playlist.stableId, playlist.title) + List(legacyColumns.size - 4) { "" } +
                listOf(playlist.isLocal.toString(), playlist.browseId.orEmpty(), playlist.bookmarked.toString()))
            playlist.tracks.forEach { appendRow(trackRow("playlist-track", playlist.stableId, playlist.title, it)) }
        }
    }

    fun read(content: String): TransferDocument {
        val rows = parseRows(content)
        val header = rows.firstOrNull()
        if (header != columns && header != legacyColumns) TransferValidation.fail("Invalid CSV header")
        val hasPlaylistMetadata = header == columns
        val library = ArrayList<TransferTrack>()
        val playlists = LinkedHashMap<String, Pair<TransferPlaylist, MutableList<TransferTrack>>>()
        for (rowIndex in 1 until rows.size) {
            val encodedRow = rows[rowIndex]
            val row = encodedRow.map(::decodeCell)
            if (row.size != header.size || row[0] != "1") TransferValidation.fail("Invalid CSV row")
            when (row[1]) {
                "library" -> {
                    if (row[2].isNotEmpty() || row[3].isNotEmpty() || row.drop(legacyColumns.size).any { it.isNotEmpty() }) TransferValidation.fail("Invalid library row")
                    library.add(readTrack(row))
                }
                "playlist" -> {
                    if (row[2].isEmpty() || playlists.containsKey(row[2]) || row.subList(4, legacyColumns.size).any { it.isNotEmpty() }) TransferValidation.fail("Invalid playlist row")
                    val playlist = TransferPlaylist(row[2], row[3], emptyList(),
                        isLocal = if (hasPlaylistMetadata) boolean(row[legacyColumns.size]) else true,
                        browseId = if (hasPlaylistMetadata) row[legacyColumns.size + 1].ifEmpty { null } else null,
                        bookmarked = if (hasPlaylistMetadata) boolean(row[legacyColumns.size + 2]) else false)
                    playlists[row[2]] = playlist to ArrayList()
                }
                "playlist-track" -> {
                    val playlist = playlists[row[2]] ?: TransferValidation.fail("Unknown playlist in CSV")
                    if (playlist.first.title != row[3] || row.drop(legacyColumns.size).any { it.isNotEmpty() }) TransferValidation.fail("Inconsistent playlist metadata")
                    playlist.second.add(readTrack(row))
                }
                else -> TransferValidation.fail("Unknown CSV row kind")
            }
            if (library.size.toLong() + playlists.values.sumOf { it.second.size.toLong() } > TransferLimits.MAX_TRACKS || playlists.size > TransferLimits.MAX_PLAYLISTS) TransferValidation.fail("Too many CSV entries")
        }
        return TransferDocument(library, playlists.values.map { (playlist, tracks) -> playlist.copy(tracks = tracks) })
    }

    private fun trackRow(kind: String, playlistId: String, playlistTitle: String, track: TransferTrack) = listOf(
        "1", kind, playlistId, playlistTitle, track.source.name.lowercase(), track.stableId, track.title,
        JsonArray(track.artists.map(::JsonPrimitive)).toString(), track.album.orEmpty(), track.durationSeconds?.toString().orEmpty(),
        track.localUri.orEmpty(), track.liked.toString(), track.inLibrary.toString(), "", "", "",
    )

    private fun readTrack(row: List<String>): TransferTrack {
        val artists = try {
            (Json.parseToJsonElement(row[7]) as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: TransferValidation.fail("Invalid artist") }
                ?: TransferValidation.fail("Invalid artists")
        } catch (error: TransferException) {
            throw error
        } catch (error: Exception) {
            throw TransferException("Invalid artists", error)
        }
        return TransferTrack(
            source = when (row[4]) {
                "youtube" -> TrackSource.YOUTUBE
                "local" -> TrackSource.LOCAL
                else -> TransferValidation.fail("Unknown track source")
            },
            stableId = row[5], title = row[6], artists = artists,
            album = row[8].ifEmpty { null },
            durationSeconds = row[9].ifEmpty { null }?.toIntOrNull() ?: if (row[9].isEmpty()) null else TransferValidation.fail("Invalid duration"),
            localUri = row[10].ifEmpty { null },
            liked = boolean(row[11]), inLibrary = boolean(row[12]),
        )
    }

    private fun boolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> TransferValidation.fail("Invalid boolean")
    }

    private fun StringBuilder.appendRow(row: List<String>) {
        val encoded = row.joinToString(",") { value ->
            val safe = encodeCell(value)
            if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
        }
        if (encoded.length > TransferLimits.MAX_LINE_CHARS) TransferValidation.fail("CSV row exceeds limit")
        append(encoded)
        append('\n')
    }

    private fun formulaLike(value: String): Boolean = value.trimStart { it.isWhitespace() || it == '\uFEFF' || it == '\u200B' }
        .firstOrNull() in setOf('=', '+', '-', '@')

    private fun encodeCell(value: String): String = if (value.startsWith("'") || formulaLike(value)) "'$value" else value

    private fun decodeCell(value: String): String {
        if (!value.startsWith("'")) return value
        val unprefixed = value.substring(1)
        return if (unprefixed.startsWith("'") || formulaLike(unprefixed)) unprefixed else value
    }

    private fun parseRows(content: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var atStart = true
        var lineLength = 0
        var index = 0
        var expectedColumns = 0
        fun endField() {
            if (row.size >= columns.size) TransferValidation.fail("Too many CSV columns")
            TransferValidation.field(field.toString(), "CSV field")
            row.add(field.toString())
            field.setLength(0)
            atStart = true
            afterQuote = false
        }
        fun endRow() {
            endField()
            if (rows.isEmpty()) {
                if (row != columns && row != legacyColumns) TransferValidation.fail("Invalid CSV header")
                expectedColumns = row.size
            } else if (row.size != expectedColumns) {
                TransferValidation.fail("Invalid CSV row width")
            }
            rows.add(row)
            if (rows.size > TransferLimits.MAX_TRACKS + TransferLimits.MAX_PLAYLISTS + 1) TransferValidation.fail("Too many CSV rows")
            row = ArrayList()
            lineLength = 0
        }
        while (index < content.length) {
            val char = content[index]
            lineLength++
            if (lineLength > TransferLimits.MAX_LINE_CHARS) TransferValidation.fail("CSV row exceeds limit")
            when {
                quoted && char == '"' && index + 1 < content.length && content[index + 1] == '"' -> {
                    field.append('"')
                    index++
                    lineLength++
                }
                quoted && char == '"' -> { quoted = false; afterQuote = true }
                quoted -> field.append(char)
                afterQuote && char == ',' -> endField()
                afterQuote && (char == '\n' || char == '\r') -> endRow()
                afterQuote -> TransferValidation.fail("Unexpected character after CSV quote")
                atStart && char == '"' -> { quoted = true; atStart = false }
                char == '"' -> TransferValidation.fail("Unexpected CSV quote")
                char == ',' -> endField()
                char == '\n' || char == '\r' -> endRow()
                else -> { field.append(char); atStart = false }
            }
            if (field.length > TransferLimits.MAX_FIELD_CHARS) TransferValidation.fail("CSV field exceeds limit")
            if (char == '\r' && !quoted && index + 1 < content.length && content[index + 1] == '\n') index++
            index++
        }
        if (quoted) TransferValidation.fail("Unterminated CSV quote")
        if (row.isNotEmpty() || field.isNotEmpty() || afterQuote) endRow()
        return rows
    }
}
