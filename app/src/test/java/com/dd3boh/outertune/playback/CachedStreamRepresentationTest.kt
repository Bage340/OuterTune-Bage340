package com.dd3boh.outertune.playback

import android.app.Application
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CachedStreamRepresentationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val opus = CachedStreamRepresentation(251, "audio/webm;codecs=opus", 8)

    @Test
    fun urlExpiryOrRestartKeepsTheRepresentationOfCachedBytes() {
        val directory = temporaryFolder.newFolder()
        SimpleCache(directory, NoOpCacheEvictor()).let { cache ->
            try {
                pinStreamRepresentation("song", listOf(cache), opus)
                writeCachedBytes(cache, "song", 0, byteArrayOf(1, 2, 3))
            } finally { cache.release() }
        }
        SimpleCache(directory, NoOpCacheEvictor()).let { reopened ->
            try {
                assertEquals(opus, cachedStreamRepresentation("song", listOf(reopened)))
                pinStreamRepresentation("song", listOf(reopened), opus)
                assertEquals(3L, reopened.getCachedBytes("song", 0, 8))
                assertThrows(IOException::class.java) {
                    pinStreamRepresentation("song", listOf(reopened), opus.copy(itag = 140, mimeType = "audio/mp4"))
                }
                assertEquals(opus, cachedStreamRepresentation("song", listOf(reopened)))
                assertEquals(3L, reopened.getCachedBytes("song", 0, 8))
                pinStreamRepresentation("unknown-length", listOf(reopened), opus.copy(contentLength = null))
                writeCachedBytes(reopened, "unknown-length", 0, byteArrayOf(4, 5, 6))
                assertThrows(IOException::class.java) {
                    pinStreamRepresentation("unknown-length", listOf(reopened), opus.copy(contentLength = 2))
                }
                assertEquals(3L, reopened.getCachedBytes("unknown-length", 0, 3))
            } finally { reopened.release() }
        }
    }

    @Test
    fun incompatiblePlayerAndDownloadBytesFailWithoutRemovingEitherCopy() {
        val player = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        val downloads = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            pinStreamRepresentation("song", listOf(player), opus)
            writeCachedBytes(player, "song", 0, byteArrayOf(1, 2, 3))
            pinStreamRepresentation("song", listOf(downloads), opus.copy(contentLength = 9))
            writeCachedBytes(downloads, "song", 0, ByteArray(9))
            assertThrows(IOException::class.java) { cachedStreamRepresentation("song", listOf(player, downloads)) }
            assertEquals(3L, player.getCachedBytes("song", 0, 8))
            assertEquals(9L, downloads.getCachedBytes("song", 0, 9))
        } finally {
            downloads.release()
            player.release()
        }
    }
}
