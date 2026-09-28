package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundaries of [shouldFetchLyrics]: the single decision for whether a fetch runs, given the stored row
 * and the current provider signature.
 */
class ShouldFetchLyricsTest {

    private val sig = "kugou,lrclib,simpmusic"
    private val now = 1_000_000_000_000L
    private val ttl = NEGATIVE_CACHE_TTL_MS

    private fun negativeRow(lastCheckedAt: Long?, signature: String?) =
        LyricsEntity(id = "v", lyrics = LYRICS_NOT_FOUND, provider = null, lastCheckedAt = lastCheckedAt, providerSignature = signature)

    @Test
    fun noRow_fetches() {
        assertTrue(shouldFetchLyrics(null, sig, now, forceRefresh = false))
    }

    @Test
    fun positiveCache_doesNotFetch() {
        val row = LyricsEntity(id = "v", lyrics = "[00:01.00]hi", provider = "LrcLib", lastCheckedAt = now, providerSignature = sig)
        assertFalse(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun positiveCache_fetchesWhenForced() {
        val row = LyricsEntity(id = "v", lyrics = "[00:01.00]hi", provider = "LrcLib", lastCheckedAt = now, providerSignature = sig)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = true))
    }

    @Test
    fun fallbackAfterAuthFailureKeepsTextAndRetriesOnlyAfterCooldown() {
        val result = RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.AUTH)
        val row = lyricsEntityForResult("v", result, sig, now)!!

        assertTrue(row.lyrics == "plain")
        assertFalse(shouldFetchLyrics(row, sig, now + PREFERRED_FAILURE_RETRY_MS - 1, forceRefresh = false))
        assertTrue(shouldFetchLyrics(row, sig, now + PREFERRED_FAILURE_RETRY_MS, forceRefresh = false))
        assertTrue(shouldFetchLyrics(row, "youtube,lrclib", now + 1, forceRefresh = false))
    }

    @Test
    fun recoveredYoutubeResultClearsPreferredFailureMarker() {
        val row = lyricsEntityForResult("v", RemoteLyricsResult.Found("YouTube Music", "[00:01]hi", true), sig, now)!!

        assertFalse(shouldFetchLyrics(row, sig, now + PREFERRED_FAILURE_RETRY_MS, forceRefresh = false))
    }

    @Test
    fun rateLimitedFallbackWaitsLongerBeforeRetry() {
        val row = lyricsEntityForResult(
            "v", RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.RATE_LIMIT), sig, now,
        )!!

        assertFalse(shouldFetchLyrics(row, sig, now + PREFERRED_FAILURE_RETRY_MS, forceRefresh = false))
        assertTrue(shouldFetchLyrics(row, sig, now + 60L * 60 * 1000, forceRefresh = false))
    }

    @Test
    fun expiredAuthFallbackRemainsVisibleWhenRevalidationIsInconclusive() {
        val previous = lyricsEntityForResult(
            "v", RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.AUTH), sig, now,
        )!!
        val checkedAt = now + PREFERRED_FAILURE_RETRY_MS
        val retained = lyricsEntityForResult("v", RemoteLyricsResult.Indeterminate, sig, checkedAt, previous)!!

        assertTrue(retained.lyrics == "plain")
        assertFalse(shouldFetchLyrics(retained, sig, checkedAt + 1, forceRefresh = false))
    }

    @Test
    fun preferredDefinitiveAbsenceKeepsEarlierFallbackTextWithoutFurtherRetry() {
        val previous = lyricsEntityForResult(
            "v", RemoteLyricsResult.Found("LrcLib", "plain", false, LyricsFailureKind.AUTH), sig, now,
        )!!
        val checkedAt = now + PREFERRED_FAILURE_RETRY_MS
        val retained = lyricsEntityForResult("v", RemoteLyricsResult.DefinitiveNotFound, sig, checkedAt, previous)!!

        assertTrue(retained.lyrics == "plain")
        assertFalse(shouldFetchLyrics(retained, sig, checkedAt + PREFERRED_FAILURE_RETRY_MS, forceRefresh = false))
    }

    @Test
    fun freshNegative_sameSignature_doesNotFetch() {
        val row = negativeRow(lastCheckedAt = now - ttl / 2, signature = sig)
        assertFalse(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_ttlExpired_fetches() {
        val row = negativeRow(lastCheckedAt = now - ttl, signature = sig)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_signatureChanged_fetches() {
        val row = negativeRow(lastCheckedAt = now - 1, signature = "kugou,lrclib")
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_nullTimestamp_fetches() {
        val row = negativeRow(lastCheckedAt = null, signature = sig)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_nullSignature_fetches() {
        val row = negativeRow(lastCheckedAt = now - 1, signature = null)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_clockRewind_fetches() {
        // Stored timestamp is in the future relative to now: the device clock moved backwards.
        val row = negativeRow(lastCheckedAt = now + 5_000L, signature = sig)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }

    @Test
    fun negative_exactlyAtTtl_fetches() {
        val row = negativeRow(lastCheckedAt = now - ttl, signature = sig)
        assertTrue(shouldFetchLyrics(row, sig, now, forceRefresh = false))
    }
}
