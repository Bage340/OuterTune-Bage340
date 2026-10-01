package com.dd3boh.outertune.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.dd3boh.outertune.utils.YTPlayerUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StreamUrlCacheTest {
    @Test
    fun getReturnsTheAtomicEntryBeforeSafeExpiry() {
        var now = 1_000L
        val cache = StreamUrlCache { now }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "client"),
            clientName = "VISIONOS",
            expiresInSeconds = 120,
        )
        now += 59_999L

        assertEquals(inserted, cache["song"])
    }

    @Test
    fun getExpiresEntrySixtySecondsBeforeServerExpiry() {
        var now = 1_000L
        val cache = StreamUrlCache { now }
        cache.put("song", "https://example.com/stream", emptyMap(), "VISIONOS", 120)
        now += 60_000L

        assertNull(cache["song"])
        assertNull(cache.invalidate("song"))
    }

    @Test
    fun shortLivedEntryIsNeverReused() {
        val cache = StreamUrlCache { 1_000L }

        cache.put("song", "https://example.com/stream", emptyMap(), "VISIONOS", 30)

        assertNull(cache["song"])
    }

    @Test
    fun invalidateReturnsAndRemovesWholeEntry() {
        val cache = StreamUrlCache { 1_000L }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "client"),
            clientName = "VISIONOS",
            expiresInSeconds = 120,
        )

        assertEquals(inserted, cache.invalidate("song"))
        assertNull(cache["song"])
    }

    @Test
    fun rejectedVisionosStreamIsSkippedUntilAReplacementIsCached() {
        val cache = StreamUrlCache { 1_000L }
        cache.put("song", "https://example.com/rejected", emptyMap(), "VISIONOS", 120)

        cache.invalidateForRetry("song")

        assertEquals("VISIONOS", cache.rejectedClient("song"))
        assertEquals("VISIONOS", cache.rejectedClient("song"))
        assertNull(cache["song"])

        cache.put("song", "https://example.com/replacement", emptyMap(), "ANDROID_VR", 120)
        assertNull(cache.rejectedClient("song"))
    }

    @Test
    fun invalidateUrlRemovesTheRejectedRequest() {
        val cache = StreamUrlCache { 1_000L }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/rejected",
            requestHeaders = emptyMap(),
            clientName = "IOS",
            expiresInSeconds = 120,
        )

        assertEquals(inserted, cache.invalidateUrl("https://example.com/rejected"))
        assertNull(cache["song"])
    }

    @Test
    fun putDefensivelyCopiesHeaders() {
        val cache = StreamUrlCache { 1_000L }
        val headers = mutableMapOf("User-Agent" to "client")
        cache.put("song", "https://example.com/stream", headers, "VISIONOS", 120)
        headers["User-Agent"] = "changed"

        assertEquals("client", cache["song"]?.requestHeaders?.get("User-Agent"))
    }

    @Test
    fun resolvedHeadersRetainCallerHeadersAndPreferIssuingClient() {
        val stream = CachedStreamUrl(
            mediaId = "song",
            url = "https://example.com/stream",
            expiresAtMillis = 61_000L,
            clientName = "VISIONOS",
            requestHeaders = mapOf("User-Agent" to "stream", "Origin" to "stream-origin"),
        )

        val headers = resolvedRequestHeaders(
            existingHeaders = mapOf("Range" to "bytes=0-100", "User-Agent" to "existing"),
            stream = stream,
        )

        assertEquals("bytes=0-100", headers["Range"])
        assertEquals("stream", headers["User-Agent"])
        assertEquals("stream-origin", headers["Origin"])
    }

    @Test
    fun rejectedClientIsExcludedEvenWhenFallbackOrderWraps() {
        for (start in 0..8) {
            val clients = YTPlayerUtils.streamClientsForAttempt(start, "VISIONOS")
            assertEquals(false, clients.any { it.clientName == "VISIONOS" })
            assertEquals(true, clients.any { it.clientName == "IOS" })
        }
    }

    @Test
    fun rejectedDownloadUrlKeepsClientUntilReplacementSucceeds() {
        val cache = StreamUrlCache { 1_000L }
        cache.put("song", "https://example.com/rejected", emptyMap(), "IOS", 120)

        cache.invalidateUrl("https://example.com/rejected")

        assertEquals("IOS", cache.rejectedClient("song"))
        assertNull(cache["song"])
        assertEquals("IOS", cache.rejectedClient("song"))
    }

    @Test
    fun parallelSongsRetainTheirOwnUrlClientAndHeaders() {
        val cache = StreamUrlCache { 1_000L }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val results = (0 until 100).map { index ->
                executor.submit {
                    val id = "song-$index"
                    val client = "client-$index"
                    val url = "https://example.com/$index"
                    cache.put(id, url, mapOf("User-Agent" to client), client, 120)
                    val stream = cache[id]
                    assertEquals(url, stream?.url)
                    assertEquals(client, stream?.clientName)
                    assertEquals(client, stream?.requestHeaders?.get("User-Agent"))
                    cache.invalidateForRetry(id)
                    assertEquals(client, cache.rejectedClient(id))
                }
            }
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun resolvedDataSpecKeepsSeekAndCacheIdentityWithIssuingClientHeaders() {
        val cache = StreamUrlCache { 1_000L }
        val stream = cache.put("song", "https://example.com/audio", mapOf("User-Agent" to "VISIONOS"), "VISIONOS", 120)
        val request = DataSpec.Builder().setUri(Uri.parse("song")).setKey("song")
            .setPosition(128L).setLength(256L).setHttpRequestHeaders(mapOf("Range" to "bytes=128-383")).build()

        val resolved = request.withResolvedStream(stream)

        assertEquals("https://example.com/audio", resolved.uri.toString())
        assertEquals("song", resolved.key)
        assertEquals(128L, resolved.position)
        assertEquals(256L, resolved.length)
        assertEquals("VISIONOS", resolved.httpRequestHeaders["User-Agent"])
        assertEquals("bytes=128-383", resolved.httpRequestHeaders["Range"])
    }
}
