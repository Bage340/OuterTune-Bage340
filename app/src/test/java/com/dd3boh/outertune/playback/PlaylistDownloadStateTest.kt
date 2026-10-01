package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.utils.getDownloadState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PlaylistDownloadStateTest {
    @Test
    fun activeTrackKeepsCancelAvailableWhenOtherTracksAreMissing() {
        assertEquals(Download.STATE_DOWNLOADING, getDownloadState(listOf(null, DownloadUtil.STATE_DOWNLOADING)))
        assertEquals(Download.STATE_DOWNLOADING, getDownloadState(listOf(DownloadUtil.STATE_DOWNLOADING, null)))
    }

    @Test
    fun emptyAndPartiallyDownloadedPlaylistsAreNotComplete() {
        val completed = LocalDateTime.of(2026, 9, 30, 12, 0)
        assertEquals(Download.STATE_STOPPED, getDownloadState(emptyList()))
        assertEquals(Download.STATE_STOPPED, getDownloadState(listOf(completed, null)))
        assertEquals(Download.STATE_STOPPED, getDownloadState(listOf(completed, DownloadUtil.STATE_INVALID)))
        assertEquals(Download.STATE_COMPLETED, getDownloadState(listOf(completed, completed)))
    }
}
