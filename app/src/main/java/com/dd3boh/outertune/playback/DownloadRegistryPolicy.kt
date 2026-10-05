package com.dd3boh.outertune.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.db.MusicDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun downloadRegistryState(
    cache: Cache,
    download: Download?,
    externalDate: LocalDateTime? = null,
): LocalDateTime? {
    if (externalDate != null) return externalDate
    return when (download?.state) {
        Download.STATE_COMPLETED -> if (hasCompleteDownloadCache(cache, download)) {
            Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        } else null
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> DownloadUtil.STATE_DOWNLOADING
        else -> null
    }
}

internal suspend fun publishDownloadRegistry(
    database: MusicDatabase,
    states: Map<String, LocalDateTime?>,
    externalPaths: Map<String, String>,
    previousLocalPathIds: Set<String>,
    registry: MutableStateFlow<Map<String, LocalDateTime>>,
) {
    database.withTransferTransaction {
        states.forEach { (id, state) ->
            when {
                state == null -> removeDownloadSong(id)
                id in externalPaths -> registerDownloadSong(id, state, externalPaths.getValue(id))
                else -> {
                    if (id in previousLocalPathIds) removeDownloadSong(id)
                    updateDownloadStatus(id, state)
                }
            }
        }
    }
    registry.value = states.mapNotNull { (id, state) -> state?.let { id to it } }.toMap()
}
