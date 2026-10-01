package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test
    fun openEndedStreamRequiresKnownCompleteCacheLength() {
        assertEquals(null, requiredCachedStreamLength(-1, 0, -1))
        assertEquals(896L, requiredCachedStreamLength(-1, 128, 1024))
        assertEquals(null, requiredCachedStreamLength(-1, 1024, 1024))
        assertEquals(100L, requiredCachedStreamLength(100, 128, -1))
    }

    @Test
    fun bulkSelectionDeduplicatesAndSkipsExistingWorkAndLocalCopies() {
        val plan = planDownloads(
            listOf(
                DownloadCandidate("local", "Local", isLocal = true),
                DownloadCandidate("file", "File", hasLocalFile = true, hasRecordedDownload = true),
                DownloadCandidate("queued", "Queued", state = Download.STATE_QUEUED, hasRecordedDownload = true),
                DownloadCandidate("active", "Active", state = Download.STATE_DOWNLOADING),
                DownloadCandidate("reserved", "Reserved", isReserved = true),
                DownloadCandidate("new", "New"),
                DownloadCandidate("new", "Duplicate"),
            )
        )

        assertEquals(listOf("new"), plan.toQueue.map { it.id })
        assertEquals(emptySet<String>(), plan.staleMarkers)
        assertEquals(1, plan.alreadyDownloaded)
        assertEquals(3, plan.alreadyQueued)
        assertEquals(1, plan.unavailable)
        assertEquals(1, plan.toQueue.size)
    }

    @Test
    fun completedDownloadNeedsActualCachedBytesBeforeItIsSkipped() {
        val plan = planDownloads(
            listOf(
                DownloadCandidate("complete", "Complete", state = Download.STATE_COMPLETED, hasCompleteCache = true),
                DownloadCandidate("stale", "Stale", state = Download.STATE_COMPLETED, hasRecordedDownload = true),
                DownloadCandidate("failed", "Failed", state = Download.STATE_FAILED, hasRecordedDownload = true),
                DownloadCandidate("orphan", "Orphan", hasRecordedDownload = true),
            )
        )

        assertEquals(listOf("stale", "failed", "orphan"), plan.toQueue.map { it.id })
        assertEquals(setOf("stale", "failed", "orphan"), plan.staleMarkers)
    }

    @Test
    fun removingDownloadIsNotReaddedWhileMedia3RemovesIt() {
        val plan = planDownloads(
            listOf(
                DownloadCandidate("removing", "Removing", state = Download.STATE_REMOVING),
                DownloadCandidate("restarting", "Restarting", state = Download.STATE_RESTARTING),
            )
        )

        assertEquals(emptyList<DownloadCandidate>(), plan.toQueue)
    }

    @Test
    fun largeDuplicateBatchesKeepOneNewRequestPerIdAndTruthfulSkipCounts() {
        for (size in listOf(0, 1, 10, 100, 1_000, 5_000)) {
            val newSongs = (0 until size).map { DownloadCandidate("new-$it", "New $it") }
            val completed = (0 until size).map {
                DownloadCandidate("complete-$it", "Completed $it", state = Download.STATE_COMPLETED, hasCompleteCache = true)
            }
            val active = (0 until size).map { DownloadCandidate("active-$it", "Active $it", state = Download.STATE_DOWNLOADING) }

            val plan = planDownloads(newSongs + completed + active + newSongs)

            assertEquals((0 until size).map { "new-$it" }, plan.toQueue.map { it.id })
            assertEquals(size, plan.alreadyDownloaded)
            assertEquals(size, plan.alreadyQueued)
            assertEquals(0, plan.unavailable)
            assertEquals(emptySet<String>(), plan.staleMarkers)
        }
    }

    @Test
    fun mixedPlaylistCancellationLeavesCompletedAndCustomDownloadsUntouched() {
        val requested = listOf("complete", "custom", "queued", "active", "failed", "missing", "queued")
        val states = mapOf(
            "complete" to Download.STATE_COMPLETED,
            "queued" to Download.STATE_QUEUED,
            "active" to Download.STATE_DOWNLOADING,
            "failed" to Download.STATE_FAILED,
        )

        assertEquals(listOf("queued", "active"), planDownloadRemoval(requested, states, cancelOnly = true))
    }

    @Test
    fun explicitRemovalIncludesCustomCopiesAndDeduplicatesSelectedIds() {
        assertEquals(
            listOf("complete", "custom"),
            planDownloadRemoval(listOf("complete", "custom", "complete"), mapOf("complete" to Download.STATE_COMPLETED), cancelOnly = false),
        )
    }
}
