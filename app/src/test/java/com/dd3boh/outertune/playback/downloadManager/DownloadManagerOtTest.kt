package com.dd3boh.outertune.playback.downloadManager

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.playback.migrateCachedDownload
import com.dd3boh.outertune.playback.writeCachedBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class DownloadManagerOtTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun migrationSaveFailureIsReportedToCallerBeforeCachedAudioCanBeRemoved() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val unavailableDirectory = DownloadDirectoryManagerOt(context, Uri.EMPTY, emptyList())
        val manager = DownloadManagerOt(unavailableDirectory)

        assertThrows(IOException::class.java) {
            manager.enqueue("R4v3-B0y", byteArrayOf(1, 2, 3), "Track")
        }
    }

    @Test
    fun migrationRetainsTheOnlyAudioCopyWhenDestinationCannotBeSaved() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = DownloadManagerOt(DownloadDirectoryManagerOt(context, Uri.EMPTY, emptyList()))
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            addCachedBytes(cache)
            assertThrows(IOException::class.java) {
                migrateCachedDownload(cache, completedDownload()) { data ->
                    manager.enqueue("song", data, "Track")
                    Uri.parse("content://test/completed-song")
                }
            }
            assertTrue(cache.isCached("song", 0, 6))
        } finally {
            cache.release()
        }
    }

    @Test
    fun migrationRemovesSourceOnlyAfterCompleteBytesReachDestination() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            addCachedBytes(cache)
            var saved = false
            migrateCachedDownload(cache, completedDownload()) { data ->
                assertTrue(cache.isCached("song", 0, 6))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), data)
                saved = true
                Uri.parse("content://test/completed-song")
            }
            assertTrue(saved)
            assertFalse(cache.isCached("song", 0, 6))
        } finally {
            cache.release()
        }
    }

    @Test
    fun incompleteSourceIsNeverPublishedOrRemovedDuringMigration() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            writeCachedBytes(cache, "song", 0L, byteArrayOf(1, 2, 3))
            var published = false
            migrateCachedDownload(cache, completedDownload()) {
                published = true
                Uri.parse("content://test/completed-song")
            }
            assertFalse(published)
            assertTrue(cache.isCached("song", 0, 3))
        } finally {
            cache.release()
        }
    }

    private fun addCachedBytes(cache: SimpleCache) {
        writeCachedBytes(cache, "song", 0L, byteArrayOf(1, 2, 3))
        writeCachedBytes(cache, "song", 3L, byteArrayOf(4, 5, 6))
    }

    private fun completedDownload() = Download(
        DownloadRequest.Builder("song", Uri.parse("https://example.com/song")).setCustomCacheKey("song").build(),
        Download.STATE_COMPLETED, 1L, 2L, 6L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
    )
}
