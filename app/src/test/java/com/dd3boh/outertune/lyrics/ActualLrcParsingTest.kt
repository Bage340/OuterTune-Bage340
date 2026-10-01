package com.dd3boh.outertune.lyrics

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActualLrcParsingTest {
    @Test fun realNonuniformTimestampsRemainExactlyProviderTiming() {
        val parsed = parseLrc("[00:01.25]First\n[00:09.876]Second\n[01:02]Third", false, false)
        assertTrue(parsed is SemanticLyrics.SyncedLyrics)
        assertEquals(listOf(1250uL, 9876uL, 62000uL), (parsed as SemanticLyrics.SyncedLyrics).text.map { it.start })
    }

    @Test fun plainAndMalformedTimingsNeverAcquireInventedSync() {
        assertTrue(parseLrc("First\nSecond", false, false) is SemanticLyrics.UnsyncedLyrics)
        assertFalse(parseLrc("[bad:time]First\n[still:bad]Second", false, false) is SemanticLyrics.SyncedLyrics)
        assertFalse(parseLrc("[00:00.00]First\n[00:00.00]Second", false, false) is SemanticLyrics.SyncedLyrics)
    }
}
