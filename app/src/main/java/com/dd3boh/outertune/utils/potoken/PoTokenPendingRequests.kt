package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.atomic.AtomicLong

internal class PoTokenPendingRequests<T> {
    private val requests = mutableMapOf<String, CancellableContinuation<T>>()
    private val nextId = AtomicLong()

    fun add(continuation: CancellableContinuation<T>, onCancellation: () -> Unit = {}): String {
        val id = nextId.incrementAndGet().toString()
        synchronized(requests) { requests[id] = continuation }
        continuation.invokeOnCancellation {
            synchronized(requests) {
                if (requests[id] === continuation) requests.remove(id)
            }
            onCancellation()
        }
        return id
    }

    fun complete(id: String, result: Result<T>): Boolean {
        val continuation = synchronized(requests) { requests.remove(id) } ?: return false
        continuation.resumeWith(result)
        return true
    }

    fun failAll(error: Throwable) {
        val pending = synchronized(requests) {
            requests.values.toList().also { requests.clear() }
        }
        pending.forEach { it.resumeWith(Result.failure(error)) }
    }
}
