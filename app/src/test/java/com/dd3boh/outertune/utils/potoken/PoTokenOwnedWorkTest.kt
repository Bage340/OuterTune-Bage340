package com.dd3boh.outertune.utils.potoken

import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenOwnedWorkTest {
    @Test
    fun repeatedCloseCancelsOwnedWorkAndDestroysOnce() {
        var scopeCancellations = 0
        var destroyed = 0
        var activeCanceled = 0
        var completedCanceled = 0
        val work = PoTokenOwnedWork({ scopeCancellations++ }, { destroyed++ })
        work.track { activeCanceled++ }
        val completed = work.track { completedCanceled++ }
        work.release(completed)
        work.close()
        work.close()
        assertEquals(1, activeCanceled)
        assertEquals(0, completedCanceled)
        assertEquals(1, scopeCancellations)
        assertEquals(1, destroyed)
    }

    @Test
    fun workRegisteredAfterCloseIsCanceledImmediately() {
        var canceled = 0
        val work = PoTokenOwnedWork({}, {})
        work.close()
        work.track { canceled++ }
        assertEquals(1, canceled)
    }
}
