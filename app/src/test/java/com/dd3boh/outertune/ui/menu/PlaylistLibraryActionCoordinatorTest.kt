package com.dd3boh.outertune.ui.menu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLibraryActionCoordinatorTest {
    @Test fun duplicateTapDoesNotClaimAnotherOperationStarted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PlaylistLibraryActionCoordinator(scope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var progressMessages = 0

        assertTrue(coordinator.launchIfIdle("playlist", { progressMessages++ }, {
            entered.complete(Unit)
            finish.await()
            completed.complete(Unit)
        }, { throw it }))
        entered.await()
        assertFalse(coordinator.launchIfIdle("playlist", { progressMessages++ }, {
            error("duplicate operation started")
        }, { throw it }))
        assertEquals(1, progressMessages)

        finish.complete(Unit)
        completed.await()
        assertTrue(coordinator.launchIfIdle("playlist", { progressMessages++ }, {}, { throw it }))
        assertEquals(2, progressMessages)
    }

    @Test fun cancellingScreenScopeDoesNotCancelStartedOperation() = runBlocking {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PlaylistLibraryActionCoordinator(applicationScope)
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()

        assertTrue(coordinator.launchIfIdle("playlist", {
            screenScope.coroutineContext[Job]!!.cancel()
        }, {
            finish.await()
            completed.complete(Unit)
        }, { throw it }))
        finish.complete(Unit)

        completed.await()
        assertTrue(coordinator.launchIfIdle("playlist", {}, {}, { throw it }))
    }

    @Test fun cancellingOperationReleasesGuard() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PlaylistLibraryActionCoordinator(scope)
        val entered = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()

        assertTrue(coordinator.launchIfIdle("playlist", {}, {
            entered.complete(Unit)
            waiting.await()
        }, { throw it }))
        entered.await()
        scope.coroutineContext[Job]!!.children.single().cancelAndJoin()

        assertTrue(coordinator.launchIfIdle("playlist", {}, {}, { throw it }))
    }

    @Test fun databaseFailureReportsErrorAndReleasesGuard() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PlaylistLibraryActionCoordinator(scope)
        var failure: Exception? = null

        assertTrue(coordinator.launchIfIdle("playlist", {}, { error("database failed") }, { failure = it }))

        assertEquals("database failed", failure?.message)
        assertTrue(coordinator.launchIfIdle("playlist", {}, {}, { throw it }))
    }
}
