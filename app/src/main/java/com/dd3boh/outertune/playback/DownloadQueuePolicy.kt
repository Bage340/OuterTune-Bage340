package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download

internal data class DownloadCandidate(
    val id: String,
    val title: String,
    val isLocal: Boolean = false,
    val hasLocalFile: Boolean = false,
    val state: Int? = null,
    val hasCompleteCache: Boolean = false,
    val hasRecordedDownload: Boolean = false,
    val isReserved: Boolean = false,
)

internal data class DownloadBatchPlan(
    val toQueue: List<DownloadCandidate>,
    val staleMarkers: Set<String>,
    val alreadyDownloaded: Int,
    val alreadyQueued: Int,
    val unavailable: Int,
)

internal fun planDownloads(candidates: List<DownloadCandidate>): DownloadBatchPlan {
    val toQueue = mutableListOf<DownloadCandidate>()
    val staleMarkers = mutableSetOf<String>()
    var alreadyDownloaded = 0
    var alreadyQueued = 0
    var unavailable = 0
    candidates.distinctBy { it.id }.forEach { candidate ->
        if (candidate.isLocal) {
            unavailable++
            return@forEach
        }
        if (candidate.hasLocalFile) {
            alreadyDownloaded++
            return@forEach
        }
        if (candidate.isReserved) {
            alreadyQueued++
            return@forEach
        }
        when (candidate.state) {
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_REMOVING,
            Download.STATE_RESTARTING -> {
                alreadyQueued++
                return@forEach
            }
            Download.STATE_COMPLETED -> if (candidate.hasCompleteCache) {
                alreadyDownloaded++
                return@forEach
            }
        }
        if (candidate.hasRecordedDownload) staleMarkers += candidate.id
        toQueue += candidate
    }
    return DownloadBatchPlan(toQueue, staleMarkers, alreadyDownloaded, alreadyQueued, unavailable)
}

internal fun requiredCachedStreamLength(requestLength: Long, position: Long, contentLength: Long): Long? =
    when {
        requestLength > 0 -> requestLength
        contentLength > position -> contentLength - position
        else -> null
    }
