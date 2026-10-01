package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ManualLyricsCacheKeyTest {
    @Test fun distinctRecordingsAndProviderSettingsDoNotShareManualResults() {
        val original = manualLyricsCacheKey("AbCdEf12345", "Same Song", "Same Artist", 180, "lrclib,youtube")

        assertNotEquals(original, manualLyricsCacheKey("ZyXwVu98765", "Same Song", "Same Artist", 180, "lrclib,youtube"))
        assertNotEquals(original, manualLyricsCacheKey("AbCdEf12345", "Same Song", "Same Artist", 240, "lrclib,youtube"))
        assertNotEquals(original, manualLyricsCacheKey("AbCdEf12345", "Same Song", "Same Artist", 180, "youtube"))
        assertEquals(original, manualLyricsCacheKey("AbCdEf12345", "Same Song", "Same Artist", 180, "lrclib,youtube"))
    }

    @Test fun separatorsAndWhitespaceCannotCollideAcrossTitleAndArtistFields() {
        assertNotEquals(manualLyricsCacheKey("id", "A-B", "C", 180, "youtube"),
            manualLyricsCacheKey("id", "B", "C-A", 180, "youtube"))
        assertNotEquals(manualLyricsCacheKey("id", "A B", "Artist", 180, "youtube"),
            manualLyricsCacheKey("id", "AB", "Artist", 180, "youtube"))
    }
}
