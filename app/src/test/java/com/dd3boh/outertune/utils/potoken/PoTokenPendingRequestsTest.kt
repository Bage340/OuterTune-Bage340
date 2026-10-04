package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class PoTokenPendingRequestsTest {
    @Test
    fun sameIdentifierRequestsCompleteIndependently() = runBlocking {
        val pending = PoTokenPendingRequests<String>()
        var firstId = ""
        var secondId = ""
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<String> { firstId = pending.add(it) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<String> { secondId = pending.add(it) }
        }
        assertNotEquals(firstId, secondId)
        pending.complete(secondId, Result.success("second"))
        pending.complete(firstId, Result.success("first"))
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun canceledRequestAndLateCallbackCannotCompleteNewerRequest() = runBlocking {
        val pending = PoTokenPendingRequests<String>()
        var oldId = ""
        var nextId = ""
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<String> { oldId = pending.add(it) }
        }
        old.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<String> { nextId = pending.add(it) }
        }
        assertFalse(pending.complete(oldId, Result.success("stale")))
        assertFalse(next.isCompleted)
        assertTrue(pending.complete(nextId, Result.success("fresh")))
        assertEquals("fresh", next.await())
    }

    @Test
    fun successErrorAndCancellationOnlyConsumeRequestOnce() = runBlocking {
        repeat(100) {
            supervisorScope {
                val pending = PoTokenPendingRequests<String>()
                var id = ""
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<String> { id = pending.add(it) }
                }
                val start = CompletableDeferred<Unit>()
                val success = async(kotlinx.coroutines.Dispatchers.Default) {
                    start.await(); pending.complete(id, Result.success("done"))
                }
                val error = async(kotlinx.coroutines.Dispatchers.Default) {
                    start.await(); pending.complete(id, Result.failure(IllegalStateException("failed")))
                }
                val cancel = async(kotlinx.coroutines.Dispatchers.Default) { start.await(); request.cancel() }
                start.complete(Unit)
                assertTrue(listOf(success.await(), error.await()).count { it } <= 1)
                cancel.await()
                runCatching { request.await() }
                assertFalse(pending.complete(id, Result.success("late")))
            }
        }
    }
}
