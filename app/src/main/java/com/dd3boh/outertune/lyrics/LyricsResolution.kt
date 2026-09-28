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

/** The preferred source owns its result even when fallback offers a timed version. */
internal suspend fun resolveWithPreferredProvider(
    providerName: String,
    fetchPreferred: suspend () -> LyricsFetchResult,
    fetchFallback: suspend () -> RemoteLyricsResult,
    classifyFound: (String) -> FoundKind,
): RemoteLyricsResult {
    var preferredWasAbsent = false
    var preferredFailure: LyricsFailureKind? = null
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
                if (kind != FoundKind.UNPARSEABLE) {
                    return RemoteLyricsResult.Found(providerName, outcome.raw, kind == FoundKind.SYNCED)
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
    return when (val fallback = fetchFallback()) {
        RemoteLyricsResult.DefinitiveNotFound, RemoteLyricsResult.Skipped ->
            if (preferredWasAbsent) RemoteLyricsResult.DefinitiveNotFound else RemoteLyricsResult.Indeterminate
        is RemoteLyricsResult.Found -> fallback.copy(preferredFailure = preferredFailure)
        else -> fallback
    }
}
