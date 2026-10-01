package com.dd3boh.outertune.playback

import androidx.media3.datasource.cache.SimpleCache

internal fun writeCachedBytes(cache: SimpleCache, key: String, position: Long, bytes: ByteArray) {
    val hole = cache.startReadWrite(key, position, bytes.size.toLong())
    try {
        val file = cache.startFile(key, position, bytes.size.toLong())
        file.writeBytes(bytes)
        cache.commitFile(file, bytes.size.toLong())
    } finally {
        cache.releaseHoleSpan(hole)
    }
}
