package com.dd3boh.outertune.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object TransferCodec {
    fun encode(format: TransferFormat, document: TransferDocument): ByteArray {
        TransferValidation.validate(document)
        val content = when (format) {
            TransferFormat.JSON -> JsonTransfer.write(document)
            TransferFormat.CSV -> CsvTransfer.write(document)
            TransferFormat.M3U8 -> M3uTransfer.write(document)
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > TransferLimits.MAX_BYTES) TransferValidation.fail("Export exceeds size limit")
        return bytes
    }

    fun decode(format: TransferFormat, bytes: ByteArray): TransferDocument {
        if (bytes.size > TransferLimits.MAX_BYTES) TransferValidation.fail("Import exceeds size limit")
        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: Exception) {
            throw TransferException("Invalid UTF-8", error)
        }
        val document = try {
            when (format) {
                TransferFormat.JSON -> JsonTransfer.read(content)
                TransferFormat.CSV -> CsvTransfer.read(content)
                TransferFormat.M3U8 -> M3uTransfer.read(content)
            }
        } catch (error: TransferException) {
            throw error
        } catch (error: Exception) {
            throw TransferException("Malformed ${format.name} import", error)
        }
        TransferValidation.validate(document)
        return document
    }
}

internal object JsonTransfer {
    fun write(document: TransferDocument): String = buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("library", JsonArray(document.library.map(::writeTrack)))
        put("playlists", JsonArray(document.playlists.map { playlist ->
            buildJsonObject {
                put("stableId", JsonPrimitive(playlist.stableId))
                put("title", JsonPrimitive(playlist.title))
                put("isLocal", JsonPrimitive(playlist.isLocal))
                put("browseId", playlist.browseId?.let(::JsonPrimitive) ?: JsonNull)
                put("bookmarked", JsonPrimitive(playlist.bookmarked))
                put("tracks", JsonArray(playlist.tracks.map(::writeTrack)))
            }
        }))
    }.toString()

    fun read(content: String): TransferDocument {
        val root = parseObject(content)
        if (root.requiredInt("schemaVersion") != 1) TransferValidation.fail("Unsupported schema version")
        val library = root.requiredArray("library").map { readTrack(it.asObject()) }
        val playlists = root.requiredArray("playlists").map { element ->
            val playlist = element.asObject()
            TransferPlaylist(
                playlist.requiredString("stableId"),
                playlist.requiredString("title"),
                playlist.requiredArray("tracks").map { readTrack(it.asObject()) },
                isLocal = playlist.optionalBoolean("isLocal") ?: true,
                browseId = playlist.optionalString("browseId"),
                bookmarked = playlist.optionalBoolean("bookmarked") ?: false,
            )
        }
        return TransferDocument(library, playlists)
    }

    fun writeTrack(track: TransferTrack): JsonObject = buildJsonObject {
        put("source", JsonPrimitive(track.source.name.lowercase()))
        put("stableId", JsonPrimitive(track.stableId))
        put("title", JsonPrimitive(track.title))
        put("artists", JsonArray(track.artists.map(::JsonPrimitive)))
        put("album", track.album?.let(::JsonPrimitive) ?: JsonNull)
        put("durationSeconds", track.durationSeconds?.let(::JsonPrimitive) ?: JsonNull)
        put("localUri", track.localUri?.let(::JsonPrimitive) ?: JsonNull)
        put("liked", JsonPrimitive(track.liked))
        put("inLibrary", JsonPrimitive(track.inLibrary))
    }

    fun readTrack(value: JsonObject): TransferTrack = TransferTrack(
        source = when (value.requiredString("source")) {
            "youtube" -> TrackSource.YOUTUBE
            "local" -> TrackSource.LOCAL
            else -> TransferValidation.fail("Unknown track source")
        },
        stableId = value.requiredString("stableId"),
        title = value.requiredString("title"),
        artists = value.requiredArray("artists").map { it.asString() },
        album = value.optionalString("album"),
        durationSeconds = value.optionalInt("durationSeconds"),
        localUri = value.optionalString("localUri"),
        liked = value.requiredBoolean("liked"),
        inLibrary = value.requiredBoolean("inLibrary"),
    )

    fun parseObject(content: String): JsonObject = try {
        Json.parseToJsonElement(content).asObject()
    } catch (error: TransferException) {
        throw error
    } catch (error: Exception) {
        throw TransferException("Malformed JSON", error)
    }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: TransferValidation.fail("Expected JSON object")
    private fun JsonElement.asString(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: TransferValidation.fail("Expected JSON string")
    private fun JsonObject.requiredString(key: String): String = get(key)?.asString() ?: TransferValidation.fail("Missing $key")
    private fun JsonObject.optionalString(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.asString()
    private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray ?: TransferValidation.fail("Missing or invalid $key")
    private fun JsonObject.requiredInt(key: String): Int = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: TransferValidation.fail("Missing or invalid $key")
    private fun JsonObject.optionalInt(key: String): Int? = get(key)?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.intOrNull
            ?: TransferValidation.fail("Invalid $key")
    }
    private fun JsonObject.requiredBoolean(key: String): Boolean = when (get(key)?.toString()) {
        "true" -> true
        "false" -> false
        else -> TransferValidation.fail("Missing or invalid $key")
    }
    private fun JsonObject.optionalBoolean(key: String): Boolean? = when (get(key)?.toString()) {
        null, "null" -> null
        "true" -> true
        "false" -> false
        else -> TransferValidation.fail("Invalid $key")
    }
}
