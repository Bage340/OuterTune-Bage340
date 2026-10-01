package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LyricsFetchStateTest {
    @Test
    fun transientFailureEndsLoadingAndRetryRunsOnce() = runBlocking {
        val coordinator = LyricsFetchCoordinator()
        var calls = 0
        coordinator.fetch("song") {
            calls++
            assertEquals(LyricsFetchStatus.LOADING, coordinator.states.value["song"])
            RemoteLyricsResult.Indeterminate
        }
        assertEquals(LyricsFetchStatus.FAILED, coordinator.states.value["song"])
        coordinator.fetch("song") {
            calls++
            RemoteLyricsResult.Found("test", "lyrics", false)
        }
        assertEquals(2, calls)
        assertEquals(LyricsFetchStatus.READY, coordinator.states.value["song"])
    }

    @Test
    fun timeoutAndExceptionTerminateLoading() = runBlocking {
        val coordinator = LyricsFetchCoordinator()
        try {
            withTimeout(20) { coordinator.fetch("timeout") { awaitCancellation() } }
            fail("timeout must propagate")
        } catch (_: CancellationException) { }
        assertEquals(LyricsFetchStatus.FAILED, coordinator.states.value["timeout"])
        try {
            coordinator.fetch("exception") { throw IllegalStateException("failed") }
            fail("exception must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(LyricsFetchStatus.FAILED, coordinator.states.value["exception"])
    }

    @Test
    fun cancellationClearsLoadingAndPropagates() = runBlocking {
        val coordinator = LyricsFetchCoordinator()
        val started = CompletableDeferred<Unit>()
        val fetch = async {
            coordinator.fetch("song") { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        fetch.cancel()
        try { fetch.await(); fail("cancel must propagate") } catch (_: CancellationException) { }
        assertEquals(LyricsFetchStatus.FAILED, coordinator.states.value["song"])
    }

    @Test
    fun lateOldRequestCannotReplaceNewRequestOrOtherTrack() = runBlocking {
        val coordinator = LyricsFetchCoordinator()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = launch {
            coordinator.fetch("song") { started.complete(Unit); release.await(); RemoteLyricsResult.Indeterminate }
        }
        started.await()
        coordinator.fetch("other") { RemoteLyricsResult.DefinitiveNotFound }
        coordinator.fetch("song") { RemoteLyricsResult.Found("test", "new", false) }
        release.complete(Unit)
        old.join()
        assertEquals(LyricsFetchStatus.READY, coordinator.states.value["song"])
        assertEquals(LyricsFetchStatus.NOT_FOUND, coordinator.states.value["other"])
    }

    @Test
    fun terminalRetentionIsBoundedAndDoesNotEvictLoading() = runBlocking {
        val coordinator = LyricsFetchCoordinator(2)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = launch {
            coordinator.fetch("active") { started.complete(Unit); release.await(); RemoteLyricsResult.Indeterminate }
        }
        started.await()
        repeat(10) { index -> coordinator.fetch("song$index") { RemoteLyricsResult.Indeterminate } }
        assertEquals(LyricsFetchStatus.LOADING, coordinator.states.value["active"])
        assertEquals(3, coordinator.states.value.size)
        release.complete(Unit)
        active.join()
        assertEquals(2, coordinator.states.value.size)
    }

    @Test
    fun displayKeepsUsableLyricsBeforeFailureOrRefreshAndAbsenceIsDefinitive() {
        LyricsFetchStatus.entries.forEach { state ->
            assertEquals(LyricsFetchStatus.READY, lyricsDisplayStatus(true, false, state))
        }
        assertEquals(LyricsFetchStatus.LOADING, lyricsDisplayStatus(false, true, LyricsFetchStatus.LOADING))
        assertEquals(LyricsFetchStatus.NOT_FOUND, lyricsDisplayStatus(false, true, LyricsFetchStatus.IDLE))
        assertEquals(LyricsFetchStatus.FAILED, lyricsDisplayStatus(false, false, LyricsFetchStatus.FAILED))
        assertEquals(LyricsFetchStatus.IDLE, lyricsDisplayStatus(false, false, LyricsFetchStatus.IDLE))
    }
}
