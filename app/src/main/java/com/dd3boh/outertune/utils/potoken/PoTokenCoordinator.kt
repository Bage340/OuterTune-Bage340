package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface PoTokenBackend {
    val isExpired: Boolean
    suspend fun initialize()
    suspend fun generatePoToken(identifier: String): String
    fun close()
}

internal class PoTokenCoordinator(
    private val timeoutMillis: Long = 30_000,
    private val create: suspend () -> PoTokenBackend,
) {
    private val mutex = Mutex()
    private class Session(val visitorData: String, val backend: PoTokenBackend, val streamingToken: String) {
        private var borrowers = 0
        private var retired = false

        fun acquire() = synchronized(this) {
            check(!retired)
            borrowers++
        }

        fun release() {
            val close = synchronized(this) {
                check(borrowers > 0)
                borrowers--
                retired && borrowers == 0
            }
            if (close) backend.close()
        }

        fun retire() {
            val close = synchronized(this) {
                check(!retired)
                retired = true
                borrowers == 0
            }
            if (close) backend.close()
        }
    }
    private var session: Session? = null

    // One optional-token budget covers the mutex, initialization, both tokens and recreation.
    // withTimeoutOrNull only consumes this deadline; cancellation by the caller still propagates.
    suspend fun get(videoId: String, visitorData: String): PoTokenResult? =
        withTimeoutOrNull(timeoutMillis) { getWithinBudget(videoId, visitorData) }

    private suspend fun getWithinBudget(
        videoId: String,
        visitorData: String,
        failedSession: Session? = null,
        allowRetry: Boolean = true,
    ): PoTokenResult {
        val (current, recreated) = mutex.withLock {
            val previous = session
            val recreate = previous == null || previous.backend.isExpired || previous.visitorData != visitorData || previous === failedSession
            val selected = if (recreate) {
                val candidate = create()
                var published = false
                try {
                    candidate.initialize()
                    val streamingToken = candidate.generatePoToken(visitorData)
                    val ready = Session(visitorData, candidate, streamingToken)
                    session = ready
                    published = true
                    // Active player requests can keep using the old backend after replacement.
                    previous?.retire()
                    ready to true
                } finally {
                    // An abandoned candidate never replaces or destroys a healthy shared session.
                    if (!published) candidate.close()
                }
            } else {
                checkNotNull(previous) to false
            }
            selected.also { it.first.acquire() }
        }
        val player = try {
            current.backend.generatePoToken(videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (recreated || !allowRetry) throw error
            // Another caller may already have replaced the failed session while we generated.
            return getWithinBudget(videoId, visitorData, failedSession = current, allowRetry = false)
        } finally {
            current.release()
        }
        return PoTokenResult(visitorData, player, current.streamingToken)
    }
}
