package com.zionhuang.innertube

import com.zionhuang.innertube.utils.completed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic library fixtures intercepted before any socket or account access. */
class LibraryResponseTest {
    private lateinit var innerTube: InnerTube
    private lateinit var originalClient: HttpClient
    private lateinit var fixtureClient: HttpClient
    private var response = ""
    private val queuedResponses = ArrayDeque<String>()

    @Before
    fun interceptTransport() {
        innerTube = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(YouTube) as InnerTube
        val field = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        originalClient = field.get(innerTube) as HttpClient
        fixtureClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            engine {
                addInterceptor { chain ->
                    val body = queuedResponses.removeFirstOrNull() ?: response
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Fixture").header("Content-Type", "application/json")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        field.set(innerTube, fixtureClient)
    }

    @After
    fun restoreTransport() {
        InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }.set(innerTube, originalClient)
        fixtureClient.close()
    }

    @Test fun missingLibraryContainerFailsInsteadOfBecomingEmpty() = runBlocking {
        response = """{"responseContext":{}}"""
        assertTrue(YouTube.library("FEmusic_liked_playlists").isFailure)
        response = initial("[]")
        assertTrue(YouTube.library("FEmusic_liked_playlists").isFailure)
        response = initial("""[{"musicShelfRenderer":{}}]""")
        assertTrue(YouTube.library("FEmusic_liked_videos").isFailure)
        response = initial(shelf("[]"))
        assertTrue(YouTube.library("FEmusic_liked_videos", -1).isFailure)
        assertTrue(YouTube.library("FEmusic_liked_videos", 1).isFailure)
    }

    @Test fun explicitEmptyGridAndShelfRemainValidCompletedLibraries() = runBlocking {
        val navigation = """{"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Create playlist"}]},"clickCommand":{}}}"""
        for (container in listOf(grid("[]"), shelf("[]"), grid("[$navigation]"))) {
            response = initial(container)
            assertTrue(YouTube.library("FEmusic_fixture").completed().getOrThrow().items.isEmpty())
        }
    }

    @Test fun expectedContainerNeedNotBeFirstSection() = runBlocking {
        response = initial("[{},${grid("[$playlist]").removeSurrounding("[", "]")}]")
        assertEquals(listOf("fixture"), YouTube.library("FEmusic_liked_playlists").getOrThrow().items.map { it.id })
    }

    @Test fun droppedGridAndShelfRowsKeepBrowseItemsButCannotComplete() = runBlocking {
        for ((container, expectedId) in listOf(
            grid("[$playlist,{}]") to "fixture",
            grid("[$playlist,${playlist.replace("Fixture playlist", "")}] ") to "fixture",
            shelf("[$song,{}]") to "song-1",
            shelf("[$song,${song.replace("\"thumbnail\":{\"musicThumbnailRenderer\":{\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://example.invalid/song.jpg\",\"width\":120,\"height\":120}]}}}", "\"thumbnail\":null")}] ") to "song-1",
        )) {
            response = initial(container)
            val page = YouTube.library("FEmusic_fixture").getOrThrow()
            assertTrue(page.items.any { it.id == expectedId })
            assertTrue(Result.success(page).completed().isFailure)
        }
    }

    @Test fun missingOrMalformedContinuationCannotTruncateCompletedLibrary() = runBlocking {
        response = initial(grid("[$playlist]", next))
        val first = YouTube.library("FEmusic_liked_playlists")
        for (body in listOf(
            """{"responseContext":{}}""",
            continuation("[$playlist,{}]"),
            continuation("[$playlist]", "[{}]"),
        )) {
            response = body
            assertTrue(first.completed().isFailure)
        }
    }

    @Test fun unconsumedOrUnknownSectionContinuationCannotCompleteLibrary() = runBlocking {
        for (metadata in listOf(next, "[{}]")) {
            response = initial(grid("[$playlist]"), metadata)
            val page = YouTube.library("FEmusic_liked_playlists").getOrThrow()
            assertEquals(listOf("fixture"), page.items.map { it.id })
            assertTrue(Result.success(page).completed().isFailure)
        }
    }

    @Test fun repeatedContinuationCannotBeAcceptedAsCompleteSnapshot() = runBlocking {
        response = initial(grid("[$playlist]", next))
        val first = YouTube.library("FEmusic_liked_playlists")
        queuedResponses.add(continuation("[$playlist]", next))
        response = continuation("[]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun validContinuationsKeepOrderAndClearConsumedToken() = runBlocking {
        response = initial(grid("[$playlist]", next))
        val first = YouTube.library("FEmusic_liked_playlists")
        response = continuation("[${playlist.replace("VLfixture", "VLsecond")}] ")
        val completed = first.completed().getOrThrow()
        assertEquals(listOf("fixture", "second"), completed.items.map { it.id })
        assertNull(completed.continuation)
    }

    @Test fun inlineShelfContinuationIsConsumedBeforeCompletion() = runBlocking {
        response = initial(shelf("[$song,$inlineNext]"))
        val first = YouTube.library("FEmusic_liked_videos")
        response = """{"responseContext":{},"continuationContents":{"musicShelfContinuation":{"contents":[${song.replace("song-1", "song-2")}]}}}"""
        assertEquals(listOf("song-1", "song-2"), first.completed().getOrThrow().items.map { it.id })
    }

    @Test fun ambiguousContainersAndContinuationMetadataCannotAuthorizeReconciliation() = runBlocking {
        response = initial("[${grid("[]").removeSurrounding("[", "]")},${shelf("[]").removeSurrounding("[", "]")}]")
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
        for (metadata in listOf("[{},${next.removeSurrounding("[", "]")}]", "[${next.removeSurrounding("[", "]")},{}]", next.replace("\"next\"", "\" \""))) {
            response = initial(grid("[$playlist]", metadata))
            assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
        }
        response = initial(shelf("[$song,${inlineNext.replace("\"next\"", "\"\"")}]"))
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
        response = initial("""[{"musicShelfRenderer":{"contents":[$song,$inlineNext],"continuations":${next.replace("\"next\"", "\"different\"")}}}]""")
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
    }

    @Test fun recentActivityRefusesDroppedRowsBeforeReplacingCachedActivity() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"sectionListContinuation":{"contents":${grid("[$playlist,{}]")}}}}"""
        assertTrue(YouTube.libraryRecentActivity().isFailure)
        response = """{"responseContext":{},"continuationContents":{"sectionListContinuation":{"contents":${grid("[]")}}}}"""
        assertTrue(YouTube.libraryRecentActivity().getOrThrow().items.isEmpty())
        response = """{"responseContext":{},"continuationContents":{"sectionListContinuation":{"contents":${grid("[$playlist]", next)}}}}"""
        assertTrue(YouTube.libraryRecentActivity().isFailure)
        response = """{"responseContext":{}}"""
        assertTrue(YouTube.libraryRecentActivity().isFailure)
    }

    @Test fun additionalUnhandledResponseRowsCannotAuthorizeReconciliation() = runBlocking {
        response = initial("""[{"gridRenderer":{"items":[$playlist]},"itemSectionRenderer":{"contents":[$song]}}]""")
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
        response = initial(grid("[$playlist]")).dropLast(1) +
            """, "onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$song]}}]}"""
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
        response = initial(grid("[$playlist]")).dropLast(1) +
            """, "continuationContents":{"gridContinuation":{"items":[$playlist]}}}"""
        assertTrue(YouTube.library("FEmusic_fixture").completed().isFailure)
    }

    private fun initial(contents: String, continuations: String? = null): String {
        val metadata = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """{"responseContext":{},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":$contents$metadata}}}}]}}}"""
    }
    private fun grid(rows: String, continuations: String? = null): String {
        val metadata = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """[{"gridRenderer":{"items":$rows$metadata}}]"""
    }
    private fun shelf(rows: String) = """[{"musicShelfRenderer":{"contents":$rows}}]"""
    private fun continuation(rows: String, continuations: String? = null): String {
        val metadata = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """{"responseContext":{},"continuationContents":{"gridContinuation":{"items":$rows$metadata}}}"""
    }

    private val next = """[{"nextContinuationData":{"continuation":"next"}}]"""
    private val inlineNext = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}"""
    private val playlist = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Fixture playlist"}]},"thumbnailRenderer":{},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLfixture","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}}"""
    private val song = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Fixture song"}]}}}],"playlistItemData":{"videoId":"song-1"},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/song.jpg","width":120,"height":120}]}}}}}"""
}
