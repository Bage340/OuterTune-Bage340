package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricsProviderTest {
    @Test fun versionedTitlesNeedCandidateIdentityThatKugouDoesNotReturn() {
        assertTrue(requiresVersionSensitiveMatching("Song (Live)"))
        assertTrue(requiresVersionSensitiveMatching("Song - Remix"))
        assertTrue(requiresVersionSensitiveMatching("Song (Sped Up)"))
        assertTrue(requiresVersionSensitiveMatching("Song (Cover)"))
        assertFalse(requiresVersionSensitiveMatching("Song of Love"))
    }

    @Test fun versionedTitleIsInconclusiveWithoutMakingNetworkRequest() = runBlocking {
        val result = KuGouLyricsProvider.getLyrics("id", "Song (Live)", "Artist", 200)
        assertTrue(result is LyricsFetchResult.Failed)
    }
}
