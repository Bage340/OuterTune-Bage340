package com.dd3boh.outertune.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.dd3boh.outertune.db.entities.FormatEntity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class CachedStreamRepresentation(val itag: Int, val mimeType: String, val contentLength: Long?)

private const val ITAG_KEY = "custom_outertune_stream_itag"
private const val MIME_KEY = "custom_outertune_stream_mime"
private const val LENGTH_KEY = "custom_outertune_stream_length"
private val representationLocks = ConcurrentHashMap<String, Any>()

internal fun <T> withStreamRepresentationLock(mediaId: String, block: () -> T): T =
    synchronized(representationLocks.getOrPut(mediaId) { Any() }, block)

internal fun canonicalStreamMimeType(mimeType: String): String =
    mimeType.lowercase().replace("\"", "").replace(" ", "")

internal fun compatibleStreamRepresentations(first: CachedStreamRepresentation, second: CachedStreamRepresentation): Boolean =
    first.itag == second.itag && canonicalStreamMimeType(first.mimeType) == canonicalStreamMimeType(second.mimeType) &&
        (first.contentLength == null || second.contentLength == null || first.contentLength == second.contentLength)

internal fun cachedStreamRepresentation(
    mediaId: String,
    caches: List<Cache>,
    legacyFormat: FormatEntity? = null,
): CachedStreamRepresentation? {
    var pinned: CachedStreamRepresentation? = null
    for (cache in caches) {
        val metadata = cache.getContentMetadata(mediaId)
        val itag = metadata.get(ITAG_KEY, -1L)
        val mime = metadata.get(MIME_KEY, "").orEmpty()
        val spans = cache.getCachedSpans(mediaId)
        val representation = if (itag > 0 && mime.isNotEmpty()) {
            CachedStreamRepresentation(itag.toInt(), mime, metadata.get(LENGTH_KEY, -1L).takeIf { it > 0 })
        } else if (spans.isNotEmpty()) {
            // Older caches did not record identity. Infer only from a compatible DB format;
            // never discard retained bytes when their representation cannot be established.
            val legacy = legacyFormat?.takeIf {
                it.itag > 0 && it.contentLength > 0 && it.mimeType.startsWith("audio/")
            } ?: throw IOException("Cached audio format is unknown; clear the partial cache before retrying")
            val cacheLength = metadata.get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            if ((cacheLength >= 0 && cacheLength != legacy.contentLength) ||
                spans.any { it.position > legacy.contentLength - it.length }) {
                throw IOException("Cached audio length does not match its recorded format")
            }
            CachedStreamRepresentation(
                legacy.itag,
                canonicalStreamMimeType(legacy.mimeType + if (legacy.codecs.isEmpty()) "" else ";codecs=${legacy.codecs}"),
                legacy.contentLength,
            )
        } else null
        if (representation != null) {
            val previous = pinned
            if (previous != null && !compatibleStreamRepresentations(previous, representation)) {
                throw IOException("Player and download caches contain different audio formats")
            }
            pinned = representation.copy(contentLength = representation.contentLength ?: previous?.contentLength)
        }
    }
    return pinned
}

internal fun pinStreamRepresentation(
    mediaId: String,
    caches: List<Cache>,
    representation: CachedStreamRepresentation,
    legacyFormat: FormatEntity? = null,
) {
    val previous = cachedStreamRepresentation(mediaId, caches, legacyFormat)
    if (previous != null && !compatibleStreamRepresentations(previous, representation)) {
        throw IOException("Resolved audio format differs from the retained cached bytes")
    }
    val pin = representation.copy(contentLength = representation.contentLength ?: previous?.contentLength)
    pin.contentLength?.let { length ->
        for (cache in caches) {
            val retainedLength = cache.getContentMetadata(mediaId).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            if ((retainedLength >= 0 && retainedLength != length) ||
                cache.getCachedSpans(mediaId).any { it.position > length - it.length }) {
                throw IOException("Resolved audio length differs from the retained cached bytes")
            }
        }
    }
    val mutations = ContentMetadataMutations()
        .set(ITAG_KEY, pin.itag.toLong())
        .set(MIME_KEY, canonicalStreamMimeType(pin.mimeType))
        .set(LENGTH_KEY, pin.contentLength ?: -1L)
    caches.forEach { it.applyContentMetadataMutations(mediaId, mutations) }
}
