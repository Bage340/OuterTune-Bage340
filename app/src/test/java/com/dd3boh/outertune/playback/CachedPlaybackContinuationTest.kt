package com.dd3boh.outertune.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CachedPlaybackContinuationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun continuousReadAcrossCachedPrefixResolvesTheUpstreamHole() {
        val prefixLength = 512 * 1024
        val bytes = ByteArray(prefixLength + 4) { (it % 127).toByte() }
        val playerCache = SimpleCache(temporaryFolder.newFolder("player"), NoOpCacheEvictor())
        val downloadCache = SimpleCache(temporaryFolder.newFolder("downloads"), NoOpCacheEvictor())
        try {
            writeCachedBytes(playerCache, "song", 0, bytes.copyOfRange(0, prefixLength))
            val remote = RemoteBytesDataSource(bytes)
            val context = ApplicationProvider.getApplicationContext<Application>()
            val source = playbackCacheDataSourceFactory(
                downloadCache,
                playerCache,
                DefaultDataSource.Factory(context, DataSource.Factory { remote }),
                { request ->
                    if (playerCache.isCached("song", request.position, MusicService.CHUNK_LENGTH)) request
                    else request.withUri(Uri.parse("https://example.test/audio"))
                },
            ).createDataSource()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                source.open(DataSpec.Builder().setUri("song").setKey("song").build())
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    output.write(buffer, 0, read)
                }
            } finally {
                source.close()
            }

            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(listOf(prefixLength.toLong()), remote.openPositions)
        } finally {
            downloadCache.release()
            playerCache.release()
        }
    }

    @Test
    fun finiteCachedReadPreservesRequestedLengthAndPosition() {
        val request = DataSpec.Builder().setUri("song").setKey("song")
            .setPosition(17).setUriPositionOffset(9).setLength(3).build()
        val bytes = byteArrayOf(4, 5, 6)
        val playerCache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        val downloadCache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            writeCachedBytes(playerCache, "song", 17, bytes)
            val source = playbackCacheDataSourceFactory(
                downloadCache, playerCache,
                DataSource.Factory { RemoteBytesDataSource(ByteArray(24)) },
                { error("Cached finite read must not resolve a stream") },
            ).createDataSource()
            try {
                assertEquals(3L, source.open(request))
                val output = ByteArray(3)
                assertEquals(3, source.read(output, 0, output.size))
                assertArrayEquals(bytes, output)
                assertEquals(C.RESULT_END_OF_INPUT, source.read(output, 0, output.size))
            } finally { source.close() }
        } finally {
            downloadCache.release()
            playerCache.release()
        }
    }

    @Test
    fun explicitPhysicalFileWinsOverStaleRemoteCacheWithTheSameMediaId() {
        val playerCache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        val downloadCache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            writeCachedBytes(playerCache, "song", 0, byteArrayOf(1, 1, 1, 1))
            val file = temporaryFolder.newFile().apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
            val context = ApplicationProvider.getApplicationContext<Application>()
            val source = playbackCacheDataSourceFactory(
                downloadCache, playerCache,
                DefaultDataSource.Factory(context, DataSource.Factory { RemoteBytesDataSource(ByteArray(4)) }),
                { error("An explicit physical file must not resolve a remote stream") },
            ).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setKey("song")
                    .setPosition(1).setUriPositionOffset(13).setLength(2).build())
                val output = ByteArray(2)
                assertEquals(2, source.read(output, 0, output.size))
                assertArrayEquals(byteArrayOf(8, 7), output)
                assertEquals(C.RESULT_END_OF_INPUT, source.read(output, 0, output.size))
            } finally { source.close() }
        } finally {
            downloadCache.release()
            playerCache.release()
        }
    }

    private class RemoteBytesDataSource(private val bytes: ByteArray) : DataSource {
        val openPositions = mutableListOf<Long>()
        private var offset = 0
        private var end = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            check(dataSpec.uri.scheme == "https") { "Unresolved media ID reached upstream" }
            openPositions += dataSpec.position
            uri = dataSpec.uri
            offset = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) bytes.size
            else minOf(bytes.size.toLong(), dataSpec.position + dataSpec.length).toInt()
            return (end - offset).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (this.offset == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - this.offset)
            bytes.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }

        override fun getUri(): Uri? = uri
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() { uri = null }
    }
}
