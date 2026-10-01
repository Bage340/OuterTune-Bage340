package com.dd3boh.outertune.lyrics

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class ManualLyricsSearchTest {
    private fun provider(name: String, result: LyricsFetchResult) = object : LyricsProvider {
        override val id = name
        override val name = name
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int, album: String?) = result
    }

    @Test fun failedSearchDeliversFallbackButIsNotCacheableAndCanRetry() = runBlocking {
        val error = IOException("offline")
        val delivered = mutableListOf<LyricsResult>()
        val errors = mutableListOf<Exception>()
        val cacheable = searchManualLyrics(
            listOf(provider("failed", LyricsFetchResult.Failed(error)), provider("fallback", LyricsFetchResult.Found("plain"))),
            "id", "Song", "Artist", 200, delivered::add, errors::add,
        )
        assertNull(cacheable)
        assertEquals(listOf(LyricsResult("fallback", "plain")), delivered)
        assertEquals(listOf(error), errors)

        val retry = searchManualLyrics(listOf(provider("recovered", LyricsFetchResult.Found("timed"))),
            "id", "Song", "Artist", 200, {}, {})
        assertEquals(listOf(LyricsResult("recovered", "timed")), retry)
    }

    @Test fun unanimousAbsenceIsCacheableButCancellationPropagates() = runBlocking {
        assertEquals(emptyList<LyricsResult>(), searchManualLyrics(listOf(provider("absent", LyricsFetchResult.NotFound)),
            "id", "Song", "Artist", 200, {}, {}))
        val cancelled = CancellationException("cancelled")
        try {
            searchManualLyrics(listOf(provider("cancelled", LyricsFetchResult.Failed(cancelled))),
                "id", "Song", "Artist", 200, {}, {})
            throw AssertionError("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
    }
}
