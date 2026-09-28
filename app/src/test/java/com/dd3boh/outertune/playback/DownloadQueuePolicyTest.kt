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
}
