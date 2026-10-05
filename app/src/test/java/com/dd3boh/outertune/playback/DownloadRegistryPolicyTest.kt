package com.dd3boh.outertune.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloadRegistryPolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun migratedIndexWithoutEitherAudioCopyIsNotReady() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            val download = entry(Download.STATE_COMPLETED)
            writeCachedBytes(cache, "song", 0, byteArrayOf(1, 2, 3, 4, 5, 6))
            val external = temporaryFolder.newFile("Song [song].mka")
            migrateCachedDownload(cache, download) { bytes ->
                external.writeBytes(bytes)
                Uri.fromFile(external)
            }
            assertFalse(hasCompleteDownloadCache(cache, download))
            assertTrue(external.delete())
            assertNull(downloadRegistryState(cache, download))
        } finally {
            cache.release()
        }
    }

    @Test fun realCompleteCacheIsReadyButTruncatedAndMissingCacheAreNot() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            val download = entry(Download.STATE_COMPLETED)
            writeCachedBytes(cache, "song", 0, byteArrayOf(1, 2, 3, 4, 5, 6))
            assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0, 0, 2_000_000), downloadRegistryState(cache, download))
            cache.getCachedSpans("song").first().file!!.writeBytes(byteArrayOf(1))
            assertNull(downloadRegistryState(cache, download))
            cache.removeResource("song")
            assertNull(downloadRegistryState(cache, download))
        } finally { cache.release() }
    }

    @Test fun validatedExternalCopyRetainsItsDateAcrossEveryIndexState() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            val date = LocalDateTime.of(2026, 9, 1, 12, 0)
            listOf(Download.STATE_COMPLETED, Download.STATE_FAILED, Download.STATE_STOPPED,
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_REMOVING,
                Download.STATE_RESTARTING).forEach { state ->
                assertEquals(date, downloadRegistryState(cache, entry(state), date))
            }
            assertEquals(date, downloadRegistryState(cache, null, date))
        } finally { cache.release() }
    }

    @Test fun onlyActiveRequestsSurviveWithoutAnAudioCopy() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING).forEach {
                assertEquals(DownloadUtil.STATE_DOWNLOADING, downloadRegistryState(cache, entry(it)))
            }
            listOf(Download.STATE_STOPPED, Download.STATE_FAILED, Download.STATE_REMOVING).forEach {
                assertNull(downloadRegistryState(cache, entry(it)))
            }
            assertNull(downloadRegistryState(cache, null))
        } finally { cache.release() }
    }

    private fun entry(state: Int) = Download(
        DownloadRequest.Builder("song", Uri.parse("https://example.com/song")).build(),
        state, 1L, 2L, 6L,
        if (state == Download.STATE_STOPPED) 1 else Download.STOP_REASON_NONE,
        if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE,
    )
}
