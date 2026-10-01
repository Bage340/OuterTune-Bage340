package com.dd3boh.outertune.lyrics

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

enum class LyricsFailureKind { TRANSIENT, AUTH, RATE_LIMIT, PARSE, INTERNAL }

internal fun classifyLyricsFailure(cause: Throwable?): LyricsFailureKind {
    val status = (cause as? ResponseException)?.response?.status?.value
        ?: Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(cause?.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return when {
        status == 401 || status == 403 -> LyricsFailureKind.AUTH
        status == 429 -> LyricsFailureKind.RATE_LIMIT
        status != null && status >= 500 -> LyricsFailureKind.TRANSIENT
        status != null -> LyricsFailureKind.INTERNAL
        cause is SerializationException -> LyricsFailureKind.PARSE
        cause is IOException -> LyricsFailureKind.TRANSIENT
        else -> LyricsFailureKind.INTERNAL
    }
}

/** Timed lyrics win; keep a usable preferred plain result if no timed fallback is found. */
internal suspend fun resolveWithPreferredProvider(
    providerName: String,
    fetchPreferred: suspend () -> LyricsFetchResult,
    fetchFallback: suspend () -> RemoteLyricsResult,
    classifyFound: (String) -> FoundKind,
): RemoteLyricsResult {
    var preferredWasAbsent = false
    var preferredFailure: LyricsFailureKind? = null
    var preferredPlain: RemoteLyricsResult.Found? = null
    var attempt = 0
    while (attempt < 2) {
        attempt++
        val outcome = try {
            fetchPreferred()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsFetchResult.Failed(e)
        }
        when (outcome) {
            is LyricsFetchResult.Found -> {
                val kind = classifyFound(outcome.raw)
                if (kind == FoundKind.SYNCED) {
                    return RemoteLyricsResult.Found(providerName, outcome.raw, synced = true)
                }
                if (kind == FoundKind.UNSYNCED) {
                    preferredPlain = RemoteLyricsResult.Found(providerName, outcome.raw, synced = false)
                    break
                }
                preferredFailure = LyricsFailureKind.PARSE
                break
            }
            LyricsFetchResult.NotFound -> {
                preferredWasAbsent = true
                break
            }
            is LyricsFetchResult.Failed -> {
                preferredFailure = classifyLyricsFailure(outcome.cause)
                if (attempt == 1 && preferredFailure == LyricsFailureKind.TRANSIENT) {
                    continue
                }
                break
            }
        }
    }
    val fallback = fetchFallback()
    if (preferredPlain != null && (fallback !is RemoteLyricsResult.Found || !fallback.synced)) return preferredPlain
    return when (fallback) {
        RemoteLyricsResult.DefinitiveNotFound, RemoteLyricsResult.Skipped ->
            if (preferredWasAbsent) RemoteLyricsResult.DefinitiveNotFound else RemoteLyricsResult.Indeterminate
        is RemoteLyricsResult.Found -> fallback.copy(preferredFailure = preferredFailure)
        else -> fallback
    }
}
