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

/** Synthetic browse fixtures, intercepted before any socket or account access. */
class PlaylistResponseTest {
    private lateinit var innerTube: InnerTube
    private lateinit var originalClient: HttpClient
    private lateinit var fixtureClient: HttpClient
    private var response = ""

    @Before
    fun interceptTransport() {
        innerTube = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(YouTube) as InnerTube
        val field = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        originalClient = field.get(innerTube) as HttpClient
        fixtureClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            engine {
                addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Fixture").header("Content-Type", "application/json")
                        .body(response.toResponseBody("application/json".toMediaType())).build()
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

    @Test fun missingHeaderIsFailure() = runBlocking {
        response = initial("[]", shelf("[]"))
        assertTrue(YouTube.playlist("PLfixture").isFailure)
    }

    @Test fun missingShelfIsFailure() = runBlocking {
        response = initial("[$header]", "[]")
        assertTrue(YouTube.playlist("PLfixture").isFailure)
    }

    @Test fun blankTitleIsFailure() = runBlocking {
        response = initial("[${header.replace("Fixture playlist", "   ")}]", shelf("[]"))
        assertTrue(YouTube.playlist("PLfixture").isFailure)
    }

    @Test fun headerAndShelfNeedNotBeFirst() = runBlocking {
        response = initial("[{},$header]", "[{},${shelf("[$song]").removeSurrounding("[", "]")}]")
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertEquals("Fixture playlist", page.playlist.title)
        assertEquals(listOf("song-1"), page.songs.map { it.id })
    }

    @Test fun editableHeaderAndExplicitEmptyShelfAreValid() = runBlocking {
        response = initial("[{\"musicEditablePlaylistDetailHeaderRenderer\":{\"header\":$header,\"editHeader\":{}}}]", shelf("[]"))
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertTrue(page.playlist.isEditable)
        assertTrue(page.songs.isEmpty())
    }

    @Test fun unparseableSongKeepsAvailableDisplayRowsButPreventsCompletedSnapshot() = runBlocking {
        response = initial("[$header]", shelf("[$song,${song.replace("\"playlistSetVideoId\":\"set-1\",", "")}]"))
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertEquals(listOf("song-1"), page.songs.map { it.id })
        assertFalse(page.snapshotComplete)
        assertTrue(Result.success(page).completed().isFailure)
    }

    @Test fun unknownShelfRowKeepsAvailableDisplayRowsButPreventsCompletedSnapshot() = runBlocking {
        response = initial("[$header]", shelf("[$song,{}]"))
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertEquals(listOf("song-1"), page.songs.map { it.id })
        assertFalse(page.snapshotComplete)
        assertTrue(Result.success(page).completed().isFailure)
    }

    @Test fun missingContinuationShapeFailsInsteadOfTruncating() = runBlocking {
        response = "{\"responseContext\":{}}"
        assertTrue(YouTube.playlistContinuation("next").isFailure)
    }

    @Test fun malformedContinuationSongKeepsAvailableDisplayRowsButMarksIncomplete() = runBlocking {
        response = "{\"responseContext\":{},\"continuationContents\":{\"musicPlaylistShelfContinuation\":{\"contents\":[$song,${song.replace("\"playlistSetVideoId\":\"set-1\",", "")}]}}}"
        val page = YouTube.playlistContinuation("next").getOrThrow()
        assertEquals(listOf("song-1"), page.songs.map { it.id })
        assertFalse(page.snapshotComplete)
    }

    @Test fun explicitEmptyContinuationIsValid() = runBlocking {
        response = "{\"responseContext\":{},\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[]}}]}"
        assertTrue(YouTube.playlistContinuation("next").getOrThrow().songs.isEmpty())
    }

    @Test fun appendActionNeedNotBeFirst() = runBlocking {
        response = "{\"responseContext\":{},\"onResponseReceivedActions\":[{},{\"appendContinuationItemsAction\":{\"continuationItems\":[$song]}}]}"
        assertEquals(listOf("song-1"), YouTube.playlistContinuation("next").getOrThrow().songs.map { it.id })
    }

    @Test fun failedContinuationMakesCompletedSnapshotFail() = runBlocking {
        response = initial("[$header]", shelf("[$song,{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"next\"}}}}]"))
        val first = YouTube.playlist("PLfixture")
        response = "{\"responseContext\":{}}"
        assertTrue(first.completed().isFailure)
    }

    @Test fun incompleteContinuationMakesCompletedSnapshotFail() = runBlocking {
        response = initial("[$header]", shelf("[$song,{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"next\"}}}}]"))
        val first = YouTube.playlist("PLfixture")
        response = "{\"responseContext\":{},\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[$song,{}]}}]}"
        assertTrue(first.completed().isFailure)
    }

    @Test fun validCompletedSnapshotKeepsFirstAndContinuationSongOrder() = runBlocking {
        response = initial("[$header]", shelf("[$song,{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"next\"}}}}]"))
        val first = YouTube.playlist("PLfixture")
        response = "{\"responseContext\":{},\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[${song.replace("song-1", "song-2").replace("set-1", "set-2")}]}}]}"
        val completed = first.completed().getOrThrow()
        assertEquals(listOf("song-1", "song-2"), completed.songs.map { it.id })
        assertTrue(completed.snapshotComplete)
        assertNull(completed.songsContinuation)
    }

    @Test fun unrecognizedContinuationMetadataCannotSilentlyTruncateCompletedSnapshot() = runBlocking {
        response = initial("[$header]", shelf("[$song,{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"next\"}}}}]"))
        val first = YouTube.playlist("PLfixture")
        response = "{\"responseContext\":{},\"continuationContents\":{\"musicPlaylistShelfContinuation\":{\"contents\":[$song],\"continuations\":[{}]}}}"
        val continuation = YouTube.playlistContinuation("next").getOrThrow()
        assertEquals(listOf("song-1"), continuation.songs.map { it.id })
        assertFalse(continuation.snapshotComplete)
        assertTrue(first.completed().isFailure)
    }

    @Test fun recognizedContinuationMetadataPreservesNextToken() = runBlocking {
        response = "{\"responseContext\":{},\"continuationContents\":{\"musicPlaylistShelfContinuation\":{\"contents\":[$song],\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"next2\"}}]}}}"
        val continuation = YouTube.playlistContinuation("next").getOrThrow()
        assertEquals("next2", continuation.continuation)
        assertTrue(continuation.snapshotComplete)
    }

    @Test fun unconsumedSectionTokenPreventsCompletedSnapshotButKeepsBrowseSongs() = runBlocking {
        response = initial("[$header]", shelf("[$song]"), "[{\"nextContinuationData\":{\"continuation\":\"section-next\"}}]")
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertEquals(listOf("song-1"), page.songs.map { it.id })
        assertEquals("section-next", page.continuation)
        assertTrue(Result.success(page).completed().isFailure)
    }

    @Test fun unrecognizedSectionContinuationMetadataMarksSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", shelf("[$song]"), "[{}]")
        val page = YouTube.playlist("PLfixture").getOrThrow()
        assertEquals(listOf("song-1"), page.songs.map { it.id })
        assertFalse(page.snapshotComplete)
        assertTrue(Result.success(page).completed().isFailure)
    }

    private fun initial(headers: String, shelves: String, continuations: String? = null): String {
        val sectionContinuations = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """{"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":$headers}}}}],"secondaryContents":{"sectionListRenderer":{"contents":$shelves$sectionContinuations}}}}}"""
    }
    private fun shelf(rows: String) = """[{"musicPlaylistShelfRenderer":{"contents":$rows,"collapsedItemCount":0}}]"""

    private val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Fixture playlist"}]},"subtitle":{"runs":[]},"buttons":[]}}"""
    private val song = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Fixture song"}]}}}],"playlistItemData":{"playlistSetVideoId":"set-1","videoId":"song-1"},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/song.jpg","width":120,"height":120}]}}}}}"""
}
