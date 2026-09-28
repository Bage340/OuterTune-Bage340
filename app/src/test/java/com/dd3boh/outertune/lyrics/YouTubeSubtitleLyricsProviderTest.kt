package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSubtitleLyricsProviderTest {
    @Test fun onlyExplicitMissingCaptionSignalsCountAsAbsence() {
        assertEquals(
            LyricsFetchResult.NotFound,
            classifySubtitleFailure(IllegalStateException("No caption tracks available for videoId=abc"))
        )
        assertEquals(
            LyricsFetchResult.NotFound,
            classifySubtitleFailure(IllegalStateException("Empty transcript for videoId=abc"))
        )
    }

    @Test fun accountFailureCannotBecomeNegativeCache() {
        val outcome = classifySubtitleFailure(IllegalStateException("Account info is not available"))
        assertEquals(LyricsFailureKind.INTERNAL, classifyLyricsFailure((outcome as LyricsFetchResult.Failed).cause))
    }
}
