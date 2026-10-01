package com.dd3boh.outertune.lyrics

import com.zionhuang.kugou.KuGou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricsProviderTest {
    @Test fun versionedTitlesKeepIdentityInKugouSearch() {
        for (title in listOf("Song (Live)", "Song - Remix", "Song (Sped Up)", "Song (Cover)")) {
            assertEquals(title, KuGou.generateKeyword(title, "Artist").title)
        }
    }

    @Test fun providerFailureDoesNotBecomeDefinitiveAbsence() {
        val result = Result.failure<String?>(IllegalStateException("KuGou server error")).toFetchResult()
        assertTrue(result is LyricsFetchResult.Failed)
    }
}
