package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class LyricsPriorityTest {
    private val classify: (String) -> FoundKind = {
        when (it) {
            "timed" -> FoundKind.SYNCED
            "plain" -> FoundKind.UNSYNCED
            else -> FoundKind.UNPARSEABLE
        }
    }

    @Test fun youtubeTimedLyricsStopFallbackSearch() = runBlocking {
        var fallbackCalls = 0
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.Found("timed") }, {
            fallbackCalls++
            RemoteLyricsResult.Found("LrcLib", "timed", true)
        }, classify)

        assertEquals(RemoteLyricsResult.Found("YouTube Music", "timed", true), result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun timedFallbackWinsOverPlainYoutubeLyrics() = runBlocking {
        var fallbackCalls = 0
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.Found("plain") }, {
            fallbackCalls++
            RemoteLyricsResult.Found("LrcLib", "timed", true)
        }, classify)

        assertEquals(RemoteLyricsResult.Found("LrcLib", "timed", true), result)
        assertEquals(1, fallbackCalls)
    }

    @Test fun plainYoutubeLyricsWinWhenFallbackHasNoTimedLyrics() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.Found("plain") }, {
            RemoteLyricsResult.Found("LrcLib", "plain", false)
        }, classify)

        assertEquals(RemoteLyricsResult.Found("YouTube Music", "plain", false), result)
    }

    @Test fun plainYoutubeLyricsRemainUsableWhenFallbackFails() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.Found("plain") }, {
            RemoteLyricsResult.Indeterminate
        }, classify)

        assertEquals(RemoteLyricsResult.Found("YouTube Music", "plain", false), result)
    }

    @Test fun definitiveYoutubeAbsenceCanUseTimedFallback() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.NotFound }, {
            RemoteLyricsResult.Found("LrcLib", "timed", true)
        }, classify)

        assertEquals(RemoteLyricsResult.Found("LrcLib", "timed", true), result)
    }

    @Test fun temporaryFailureRetriesThenUsesPlainFallbackWithoutNegativeCache() = runBlocking {
        var attempts = 0
        val result = resolveWithPreferredProvider("YouTube Music", {
            attempts++
            LyricsFetchResult.Failed(IOException("connection reset"))
        }, { RemoteLyricsResult.Found("SimpMusic", "plain", false) }, classify)

        assertEquals(2, attempts)
        assertEquals(RemoteLyricsResult.Found("SimpMusic", "plain", false, LyricsFailureKind.TRANSIENT), result)
    }

    @Test fun temporaryFailureAndAbsentFallbackRemainIndeterminate() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", {
            LyricsFetchResult.Failed(IOException("connection reset"))
        }, { RemoteLyricsResult.DefinitiveNotFound }, classify)

        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    @Test fun authFailureDoesNotRetryOrBecomeMissingLyrics() = runBlocking {
        var attempts = 0
        val result = resolveWithPreferredProvider("YouTube Music", {
            attempts++
            LyricsFetchResult.Failed(IOException("HTTP 401"))
        }, { RemoteLyricsResult.DefinitiveNotFound }, classify)

        assertEquals(1, attempts)
        assertEquals(RemoteLyricsResult.Indeterminate, result)
        assertEquals(LyricsFailureKind.AUTH, classifyLyricsFailure(IOException("HTTP 401")))
    }

    @Test fun authFailureWithFallbackRetainsFailureForLaterYoutubeRecovery() = runBlocking {
        var attempts = 0
        val fallback = resolveWithPreferredProvider("YouTube Music", {
            attempts++
            LyricsFetchResult.Failed(IOException("HTTP 401"))
        }, { RemoteLyricsResult.Found("LrcLib", "plain", false) }, classify)

        assertEquals(1, attempts)
        assertEquals(RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.AUTH), fallback)

        val recovered = resolveWithPreferredProvider("YouTube Music", {
            attempts++
            LyricsFetchResult.Found("timed")
        }, { error("Fallback must not run after YouTube recovers") }, classify)

        assertEquals(2, attempts)
        assertEquals(RemoteLyricsResult.Found("YouTube Music", "timed", true), recovered)
    }

    @Test fun rateLimitWithFallbackRetainsFailureWithoutRetryingImmediately() = runBlocking {
        var attempts = 0
        val result = resolveWithPreferredProvider("YouTube Music", {
            attempts++
            LyricsFetchResult.Failed(IOException("HTTP 429"))
        }, { RemoteLyricsResult.Found("LrcLib", "plain", false) }, classify)

        assertEquals(1, attempts)
        assertEquals(RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.RATE_LIMIT), result)
    }

    @Test fun allProvidersDefinitivelyMissingCanBeNegativeCached() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.NotFound }, {
            RemoteLyricsResult.DefinitiveNotFound
        }, classify)

        assertEquals(RemoteLyricsResult.DefinitiveNotFound, result)
    }

    @Test fun offlinePlaybackDoesNotBecomeMissingLyrics() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", {
            LyricsFetchResult.Failed(UnknownHostException("offline"))
        }, { RemoteLyricsResult.Indeterminate }, classify)

        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    @Test fun malformedYoutubeTextCannotProduceNegativeCache() = runBlocking {
        val result = resolveWithPreferredProvider("YouTube Music", { LyricsFetchResult.Found("garbage") }, {
            RemoteLyricsResult.DefinitiveNotFound
        }, classify)

        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }
}
