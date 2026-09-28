package com.dd3boh.simpmusic

import com.dd3boh.simpmusic.models.LyricsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpMusicLyricsMatchingTest {
    @Test fun wrongVideoIdCannotSupplyTimedLyrics() {
        val candidates = listOf(
            LyricsData(videoId = "other", duration = 200, syncedLyrics = "wrong timed"),
            LyricsData(videoId = "wanted", duration = 200, plainLyrics = "right plain")
        )

        assertEquals("right plain", with(SimpMusicLyrics) { candidates.selectBestRaw("wanted", 200) })
    }

    @Test fun wrongDurationCandidateIsRejectedEvenWhenItIsTheOnlyResult() {
        val candidates = listOf(LyricsData(videoId = "wanted", duration = 300, syncedLyrics = "wrong"))

        assertNull(with(SimpMusicLyrics) { candidates.selectBestRaw("wanted", 200) })
    }

    @Test fun genuineTimedLyricsBeatPlainVersion() {
        val candidates = listOf(
            LyricsData(videoId = "wanted", duration = 200, plainLyrics = "plain"),
            LyricsData(videoId = "wanted", duration = 202, syncedLyrics = "timed")
        )

        assertEquals("timed", with(SimpMusicLyrics) { candidates.selectBestRaw("wanted", 200) })
    }
}
