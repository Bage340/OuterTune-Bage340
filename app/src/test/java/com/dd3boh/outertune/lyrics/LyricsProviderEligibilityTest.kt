package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsProviderEligibilityTest {
    @Test fun localSongSkipsVideoIdOnlyProviders() {
        val providers = listOf(
            YouTubeLyricsProvider,
            LrcLibLyricsProvider,
            SimpMusicLyricsProvider,
            KuGouLyricsProvider,
            YouTubeSubtitleLyricsProvider
        )

        assertEquals(
            listOf("lrclib", "kugou"),
            eligibleLyricsProviders(providers, isLocal = true).map { it.id }
        )
    }

    @Test fun youtubeSongKeepsPreferredAndFallbackProviders() {
        val providers = listOf(YouTubeLyricsProvider, LrcLibLyricsProvider, SimpMusicLyricsProvider)

        assertEquals(
            listOf("youtube", "lrclib", "simpmusic"),
            eligibleLyricsProviders(providers, isLocal = false).map { it.id }
        )
    }
}
