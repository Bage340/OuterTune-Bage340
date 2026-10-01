package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LyricsFetchStatus { IDLE, LOADING, READY, NOT_FOUND, FAILED }

/** Ephemeral UI state. Failed requests never create a negative database cache. */
internal class LyricsFetchCoordinator(private val terminalLimit: Int = 32) {
    private val mutableStates = MutableStateFlow<Map<String, LyricsFetchStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val requests = mutableMapOf<String, Long>()
    private var nextRequest = 0L

    suspend fun fetch(mediaId: String, block: suspend () -> RemoteLyricsResult?) {
        val request = synchronized(this) {
            val token = ++nextRequest
            requests[mediaId] = token
            mutableStates.value = mutableStates.value + (mediaId to LyricsFetchStatus.LOADING)
            token
        }
        var terminal = LyricsFetchStatus.FAILED
        try {
            terminal = when (block()) {
                is RemoteLyricsResult.Found -> LyricsFetchStatus.READY
                RemoteLyricsResult.DefinitiveNotFound -> LyricsFetchStatus.NOT_FOUND
                RemoteLyricsResult.Indeterminate, RemoteLyricsResult.Skipped -> LyricsFetchStatus.FAILED
                null -> LyricsFetchStatus.IDLE // A concurrent fetch already populated the cache.
            }
        } finally {
            synchronized(this) {
                if (requests[mediaId] == request) {
                    requests.remove(mediaId)
                    val updated = LinkedHashMap(mutableStates.value)
                    updated.remove(mediaId)
                    updated[mediaId] = terminal
                    val terminals = updated.keys.filterNot { it in requests }
                    terminals.take((terminals.size - terminalLimit).coerceAtLeast(0)).forEach(updated::remove)
                    mutableStates.value = updated
                }
            }
        }
    }
}

/** Parsed cache/local lyrics remain visible while a refresh runs or fails. */
internal fun lyricsDisplayStatus(
    hasLyrics: Boolean,
    definitiveAbsence: Boolean,
    fetchStatus: LyricsFetchStatus,
): LyricsFetchStatus = when {
    hasLyrics -> LyricsFetchStatus.READY
    fetchStatus == LyricsFetchStatus.LOADING -> LyricsFetchStatus.LOADING
    definitiveAbsence -> LyricsFetchStatus.NOT_FOUND
    fetchStatus == LyricsFetchStatus.NOT_FOUND -> LyricsFetchStatus.NOT_FOUND
    fetchStatus == LyricsFetchStatus.FAILED -> LyricsFetchStatus.FAILED
    else -> LyricsFetchStatus.IDLE
}
