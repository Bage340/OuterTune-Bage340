package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource

internal fun playbackCacheDataSourceFactory(
    downloadCache: Cache,
    playerCache: Cache,
    upstreamFactory: DataSource.Factory,
    streamResolver: ResolvingDataSource.Resolver,
    playerSinkFactory: DataSink.Factory? = null,
): DataSource.Factory {
    val cachedFactory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(
            CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(upstreamFactory, streamResolver))
                .setCacheWriteDataSinkFactory(playerSinkFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return DataSource.Factory {
        PhysicalPlaybackDataSource(upstreamFactory.createDataSource(), cachedFactory.createDataSource())
    }
}

private class PhysicalPlaybackDataSource(
    private val physical: DataSource,
    private val cached: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "file" || dataSpec.uri.scheme == "content") physical else cached
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun addTransferListener(transferListener: TransferListener) {
        physical.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
    }

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
