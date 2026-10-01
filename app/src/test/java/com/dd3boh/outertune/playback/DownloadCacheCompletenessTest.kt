package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.common.C
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloadCacheCompletenessTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun completedIndexEntryIsNotPlayableOfflineUntilEveryByteIsCached() {
        val cache = SimpleCache(temporaryFolder.newFolder("cache"), NoOpCacheEvictor())
        try {
            val download = Download(
                DownloadRequest.Builder("song", Uri.parse("https://example.com/song"))
                    .setCustomCacheKey("song")
                    .build(),
                Download.STATE_COMPLETED,
                1L,
                2L,
                6L,
                Download.STOP_REASON_NONE,
                Download.FAILURE_REASON_NONE,
            )
            writeCachedBytes(cache, "song", 0L, byteArrayOf(1, 2, 3))

            assertFalse(hasCompleteDownloadCache(cache, download))

            writeCachedBytes(cache, "song", 3L, byteArrayOf(4, 5, 6))
            assertTrue(hasCompleteDownloadCache(cache, download))
        } finally {
            cache.release()
        }
    }

    @Test
    fun unknownLengthDoesNotTreatOneCachedByteAsACompleteOpenEndedDownload() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            val download = completedDownload(C.LENGTH_UNSET.toLong())
            writeCachedBytes(cache, "song", 0L, byteArrayOf(1))
            assertFalse(hasCompleteDownloadCache(cache, download))
            cache.applyContentMetadataMutations("song", ContentMetadataMutations().set("exo_len", 6L))
            assertFalse(hasCompleteDownloadCache(cache, download))
        } finally {
            cache.release()
        }
    }

    @Test
    fun metadataLengthAndCustomCacheKeyProveUnknownLengthDownloadCompleteness() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            val download = completedDownload(-1L, "audio-key")
            writeCachedBytes(cache, "audio-key", 0L, byteArrayOf(1, 2, 3, 4, 5, 6))
            cache.applyContentMetadataMutations("audio-key", ContentMetadataMutations().set("exo_len", 6L))
            assertTrue(hasCompleteDownloadCache(cache, download))
        } finally {
            cache.release()
        }
    }

    @Test
    fun externallyTruncatedCacheFileDoesNotCountAsAnOfflineCopy() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            writeCachedBytes(cache, "song", 0L, byteArrayOf(1, 2, 3, 4, 5, 6))
            cache.getCachedSpans("song").first().file!!.writeBytes(byteArrayOf(1))

            assertFalse(hasCompleteDownloadCache(cache, completedDownload(6L)))
        } finally {
            cache.release()
        }
    }

    @Test
    fun externallyDeletedCacheFileDoesNotCountAsAnOfflineCopy() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor())
        try {
            writeCachedBytes(cache, "song", 0L, byteArrayOf(1, 2, 3, 4, 5, 6))
            assertTrue(cache.getCachedSpans("song").first().file!!.delete())

            assertFalse(hasCompleteDownloadCache(cache, completedDownload(6L)))
        } finally {
            cache.release()
        }
    }

    private fun completedDownload(length: Long, key: String = "song") = Download(
        DownloadRequest.Builder("song", Uri.parse("https://example.com/song")).setCustomCacheKey(key).build(),
        Download.STATE_COMPLETED, 1L, 2L, length, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
    )
}
