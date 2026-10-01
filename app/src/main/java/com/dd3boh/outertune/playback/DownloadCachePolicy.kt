package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import java.io.ByteArrayOutputStream
import java.io.IOException

internal fun hasCompleteDownloadCache(cache: Cache, download: Download): Boolean {
    if (download.state != Download.STATE_COMPLETED) return false
    val cacheKey = download.request.customCacheKey ?: download.request.id
    val metadataLength = cache.getContentMetadata(cacheKey).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
    val expectedLength = maxOf(download.contentLength, metadataLength)
    if (expectedLength <= 0 || !cache.isCached(cacheKey, 0, expectedLength)) return false
    return cache.getCachedSpans(cacheKey).filter { it.position < expectedLength }.all { span ->
        val file = span.file
        file != null && file.isFile && file.canRead() &&
            file.length() >= minOf(span.length, expectedLength - span.position)
    }
}

internal fun migrateCachedDownload(cache: Cache, download: Download, save: (ByteArray) -> Uri): Uri? {
    if (!hasCompleteDownloadCache(cache, download)) return null
    val cacheKey = download.request.customCacheKey ?: download.request.id
    val data = ByteArrayOutputStream().use { output ->
        for (span in cache.getCachedSpans(cacheKey)) {
            val file = span.file ?: throw IOException("Cached download file is unavailable")
            file.inputStream().use { it.copyTo(output) }
        }
        output.toByteArray()
    }
    val savedFile = save(data)
    cache.removeResource(cacheKey)
    return savedFile
}
