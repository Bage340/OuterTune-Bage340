package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.constants.KeepScreenOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnPolicyTest {
    @Test
    fun neverModeDoesNotKeepScreenOnEvenWithVisibleLyrics() {
        assertFalse(shouldKeepScreenOn(KeepScreenOn.NEVER, true, true, true, true))
    }

    @Test
    fun playerModeKeepsScreenOnWithoutLyrics() {
        assertTrue(shouldKeepScreenOn(KeepScreenOn.PLAYER, true, true, false, true))
    }

    @Test
    fun lyricsModeRequiresVisibleLyrics() {
        assertTrue(shouldKeepScreenOn(KeepScreenOn.LYRICS, true, true, true, true))
        assertFalse(shouldKeepScreenOn(KeepScreenOn.LYRICS, true, true, false, true))
    }

    @Test
    fun pauseReleasesScreenInEveryMode() {
        KeepScreenOn.entries.forEach { mode ->
            assertFalse(shouldKeepScreenOn(mode, false, true, true, true))
        }
    }

    @Test
    fun hiddenOrCollapsedPlayerReleasesScreenInEveryMode() {
        KeepScreenOn.entries.forEach { mode ->
            assertFalse(shouldKeepScreenOn(mode, true, false, true, true))
        }
    }

    @Test
    fun inactiveLifecycleReleasesScreenInEveryMode() {
        KeepScreenOn.entries.forEach { mode ->
            assertFalse(shouldKeepScreenOn(mode, true, true, true, false))
        }
    }
}
