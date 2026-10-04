package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PoTokenCoordinatorTest {
    @Test
    fun replacingSessionKeepsBackendAliveUntilItsPlayerRequestFinishes() = runBlocking {
        val player = CompletableDeferred<String>()
        val old = Backend(generate = { if (it == "blocked") player.await() else "old:$it" })
        val replacement = Backend(generate = { "new:$it" })
        var creations = 0
        val coordinator = PoTokenCoordinator(500) { if (creations++ == 0) old else replacement }
        coordinator.get("warmup", "session")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("blocked", "session") }
        assertEquals("new:other-session", coordinator.get("other", "other-session")?.streamingDataPoToken)
        assertEquals(0, old.closes)
        player.complete("old:blocked")
        assertEquals("old:blocked", pending.await()?.playerRequestPoToken)
        assertEquals(1, old.closes)
        assertEquals(0, replacement.closes)
    }

    @Test
    fun twoFailuresFromOldSessionReuseTheAlreadyPublishedReplacement() = runBlocking {
        val failFirst = CompletableDeferred<Unit>()
        val failSecond = CompletableDeferred<Unit>()
        val old = Backend(generate = {
            when (it) {
                "first" -> { failFirst.await(); throw IllegalStateException("old first failure") }
                "second" -> { failSecond.await(); throw IllegalStateException("old second failure") }
                else -> "old:$it"
            }
        })
        val replacement = Backend(generate = { "new:$it" })
        var creations = 0
        val coordinator = PoTokenCoordinator(500) { if (creations++ == 0) old else replacement }
        coordinator.get("warmup", "session")
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("first", "session") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("second", "session") }
        failFirst.complete(Unit)
        assertEquals("new:first", first.await()?.playerRequestPoToken)
        failSecond.complete(Unit)
        assertEquals("new:second", second.await()?.playerRequestPoToken)
        assertEquals(2, creations)
        assertEquals(1, old.closes)
        assertEquals(0, replacement.closes)
    }

    @Test
    fun initializationAndStreamingShareOneBudget() = runBlocking {
        var playerRequests = 0
        val backend = Backend(
            initialize = { delay(60) },
            generate = {
                if (it == "session") { delay(60); "stream:session" }
                else { playerRequests++; "player:$it" }
            },
        )
        val coordinator = PoTokenCoordinator(100) { backend }
        assertNull(withTimeout(500) { coordinator.get("video", "session") })
        assertEquals(1, backend.closes)
        assertEquals(0, playerRequests)
    }

    @Test
    fun recreationRetryUsesTheRemainingBudget() = runBlocking {
        val first = Backend(generate = {
            if (it == "broken") { delay(60); throw IllegalStateException("lost content") }
            if (it == "session") "stream:session" else "player:$it"
        })
        val replacement = Backend(initialize = { delay(60) })
        var creations = 0
        val coordinator = PoTokenCoordinator(100) { if (creations++ == 0) first else replacement }
        coordinator.get("warmup", "session")
        assertNull(withTimeout(500) { coordinator.get("broken", "session") })
        assertEquals(2, creations)
        assertEquals(1, replacement.closes)
        assertEquals(0, first.closes)
    }

    @Test
    fun backendFailureRecreatesOnceAndKeepsStreamingBeforePlayerOrder() = runBlocking {
        val first = Backend(generate = {
            if (it == "broken") throw IllegalStateException("lost content")
            "token:$it"
        })
        val order = mutableListOf<String>()
        val replacement = Backend(generate = { order.add(it); "new:$it" })
        var creations = 0
        val coordinator = PoTokenCoordinator(500) { if (creations++ == 0) first else replacement }
        coordinator.get("warmup", "session")
        val recovered = coordinator.get("broken", "session")
        assertEquals("new:session", recovered?.streamingDataPoToken)
        assertEquals("new:broken", recovered?.playerRequestPoToken)
        assertEquals(listOf("session", "broken"), order)
        assertEquals(2, creations)
        assertEquals(1, first.closes)
    }

    @Test
    fun changingSessionMintsTokensAgainstTheNewVisitorData() = runBlocking {
        var creations = 0
        val coordinator = PoTokenCoordinator(500) {
            creations++
            Backend(generate = { "token:$it" })
        }
        coordinator.get("video", "session")
        val changed = coordinator.get("video", "other-session")
        assertEquals("other-session", changed?.visitorData)
        assertEquals("token:other-session", changed?.streamingDataPoToken)
        assertEquals("token:video", changed?.playerRequestPoToken)
        assertEquals(2, creations)
    }

    @Test
    fun lostInitializationExpiresClosesCandidateAndNextRequestWorks() = runBlocking {
        val abandoned = Backend(initialize = { awaitCancellation() })
        val healthy = Backend()
        var creations = 0
        val coordinator = PoTokenCoordinator(timeoutMillis = 50) {
            if (creations++ == 0) abandoned else healthy
        }
        val first = withTimeout(500) { coordinator.get("video", "session") }
        assertNull(first)
        assertEquals(1, abandoned.closes)
        assertEquals("player:next", coordinator.get("next", "session")?.playerRequestPoToken)
        assertEquals(2, creations)
    }

    @Test
    fun lostStreamingTokenNeverPublishesPartialSession() = runBlocking {
        val abandoned = Backend(generate = { awaitCancellation() })
        val healthy = Backend()
        var creations = 0
        val coordinator = PoTokenCoordinator(50) { if (creations++ == 0) abandoned else healthy }
        assertNull(withTimeout(500) { coordinator.get("video", "session") })
        assertEquals(1, abandoned.closes)
        assertEquals("stream:session", coordinator.get("next", "session")?.streamingDataPoToken)
        assertEquals(2, creations)
    }

    @Test
    fun parentCancellationDoesNotRetryOrDestroyHealthyBackend() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val backend = Backend(generate = {
            if (it == "blocked") { started.complete(Unit); awaitCancellation() }
            if (it == "session") "stream:session" else "player:$it"
        })
        var creations = 0
        val coordinator = PoTokenCoordinator(500) { creations++; backend }
        coordinator.get("warmup", "session")
        val request = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("blocked", "session") }
        started.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(1, creations)
        assertEquals(0, backend.closes)
        assertEquals("player:next", coordinator.get("next", "session")?.playerRequestPoToken)
    }

    @Test
    fun tokenTimeoutDoesNotCancelAnotherRequestOnHealthyBackend() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val backend = Backend(generate = {
            if (it == "blocked") { entered.complete(Unit); awaitCancellation() }
            if (it == "session") "stream:session" else "player:$it"
        })
        val coordinator = PoTokenCoordinator(50) { backend }
        coordinator.get("warmup", "session")
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("blocked", "session") }
        entered.await()
        assertEquals("player:other", coordinator.get("other", "session")?.playerRequestPoToken)
        assertNull(withTimeout(500) { blocked.await() })
        assertEquals(0, backend.closes)
    }

    @Test
    fun timeoutWaitingForMutexDoesNotCloseTheOwnersCandidate() = runBlocking {
        val initialized = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Backend(initialize = { initialized.complete(Unit); release.await() })
        val coordinator = PoTokenCoordinator(150) { backend }
        val owner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.get("owner", "session") }
        initialized.await()
        // A caller's shorter parent deadline must only cancel its own mutex waiter.
        val waiter = async { kotlinx.coroutines.withTimeoutOrNull(30) { coordinator.get("waiter", "session") } }
        assertNull(waiter.await())
        assertEquals(0, backend.closes)
        release.complete(Unit)
        assertEquals("player:owner", owner.await()?.playerRequestPoToken)
        assertEquals(0, backend.closes)
    }

    private class Backend(
        private val initialize: suspend () -> Unit = {},
        private val generate: suspend (String) -> String = { if (it == "session") "stream:session" else "player:$it" },
    ) : PoTokenBackend {
        var closes = 0
        private val activeRequests = mutableSetOf<Job>()
        override val isExpired = false
        override suspend fun initialize() = initialize.invoke()
        override suspend fun generatePoToken(identifier: String): String {
            val request = currentCoroutineContext().job
            synchronized(activeRequests) { activeRequests.add(request) }
            return try {
                generate(identifier)
            } finally {
                synchronized(activeRequests) { activeRequests.remove(request) }
            }
        }
        override fun close() {
            closes++
            synchronized(activeRequests) { activeRequests.toList() }.forEach {
                it.cancel(CancellationException("Backend disposed with pending token request"))
            }
        }
    }
}
