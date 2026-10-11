package com.zionhuang.innertube

import com.zionhuang.innertube.utils.completed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okio.Buffer
import java.io.IOException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Synthetic browse fixtures, intercepted before any socket or account access. */
class PlaylistResponseTest {
    private lateinit var innerTube: InnerTube
    private lateinit var originalClient: HttpClient
    private lateinit var fixtureClient: HttpClient
    private var response = ""
    private var continuationResponse: ((String?) -> String)? = null

    @Before
    fun interceptTransport() {
        innerTube = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(YouTube) as InnerTube
        val field = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        originalClient = field.get(innerTube) as HttpClient
        fixtureClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            engine {
                addInterceptor { chain ->
                    val requestBody = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                    val token = Json.parseToJsonElement(requestBody).jsonObject["continuation"]?.jsonPrimitive?.content
                    val payload = continuationResponse?.invoke(token) ?: response
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Fixture").header("Content-Type", "application/json")
                        .body(payload.toResponseBody("application/json".toMediaType())).build()
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
        response = initial("[{},$header]", "[${carousel("[$playlistCard]")},${shelf("[$song]").removeSurrounding("[", "]")}]")
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
        response = "{\"responseContext\":{},\"onResponseReceivedActions\":[{\"clickTrackingParams\":\"fixture\"},{\"appendContinuationItemsAction\":{\"continuationItems\":[$song]}}]}"
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

    @Test fun completedConsumesSeparatePlaylistRecommendationsWithoutAddingMemberships() = runBlocking {
        response = initial("[$header]", shelf("[$song]"), "[{\"nextContinuationData\":{\"continuation\":\"section-next\"}}]")
        val first = YouTube.playlist("PLfixture")
        response = sectionResponse("[$playlistCard]")
        val completed = first.completed().getOrThrow()
        assertEquals(listOf("song-1"), completed.songs.map { it.id })
        assertNull(completed.continuation)
        assertNull(completed.songsContinuation)
        assertTrue(completed.snapshotComplete)
    }

    @Test fun songAndSectionPaginationKeepMembershipOrderAndConsumeEverySectionPage() = runBlocking {
        response = initial("[$header]", shelf("[$song,${inlineToken("songs-next")}]"), metadata("section-next"))
        val first = YouTube.playlist("PLfixture")
        continuationResponse = { token ->
            when (token) {
                "songs-next" -> """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${song.replace("song-1", "song-2").replace("set-1", "set-2")}]}}}"""
                "section-next" -> sectionResponse("[$playlistCard]", metadata("section-last"))
                "section-last" -> sectionResponse("[$playlistCard]")
                else -> error("Unexpected continuation")
            }
        }
        val completed = first.completed().getOrThrow()
        assertEquals(listOf("song-1", "song-2"), completed.songs.map { it.id })
        assertNull(completed.continuation)
    }

    @Test fun sectionUnknownRendererSiblingCannotBeHiddenByLenientDecoder() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[$playlistCard]").replace("\"musicCarouselShelfRenderer\":", "\"unknownSongRenderer\":{},\"musicCarouselShelfRenderer\":")
        assertTrue(first.completed().isFailure)
    }

    @Test fun recommendationCardUnknownSiblingCannotBeHiddenByLenientDecoder() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[${playlistCard.replace("\"musicTwoRowItemRenderer\":", "\"unknownSongRenderer\":{},\"musicTwoRowItemRenderer\":")}]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun sectionMixedPlaylistAndSongRowsCannotCompleteMembershipSnapshot() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[$playlistCard,$song]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun songNavigationInsideTwoRowCardCannotCompleteMembershipSnapshot() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[${playlistCard.replace("\"navigationEndpoint\":{", "\"navigationEndpoint\":{\"watchEndpoint\":{\"videoId\":\"song-other\"},")}]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun nonPlaylistBrowseCardCannotCompleteMembershipSnapshot() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[${playlistCard.replace("MUSIC_PAGE_TYPE_PLAYLIST", "MUSIC_PAGE_TYPE_ALBUM")}]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun blankPlaylistBrowseIdCannotCompleteMembershipSnapshot() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[${playlistCard.replace("VLPLrecommendation", "   ")}]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun malformedOrAmbiguousSectionTokenCannotCompleteSnapshot() = runBlocking {
        for (tokens in listOf("[{}]", metadata(" "), "${metadata("one").dropLast(1)},${metadata("two").drop(1)}")) {
            val first = sectionInitial()
            response = sectionResponse("[$playlistCard]", tokens)
            assertTrue(first.completed().isFailure)
        }
    }

    @Test fun duplicateInitialSectionTokensMarkSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", shelf("[$song]"), "${metadata("one").dropLast(1)},${metadata("two").drop(1)}")
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun duplicateInitialSongShelvesMarkSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", "[${shelf("[$song]").removeSurrounding("[", "]")},${shelf("[$song]").removeSurrounding("[", "]")}]")
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun unknownInitialSectionCannotSilentlyHideMemberships() = runBlocking {
        response = initial("[$header]", "[${shelf("[$song]").removeSurrounding("[", "]")},{\"unknownSongShelfRenderer\":{\"contents\":[]}}]")
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun knownSongRowWithUnknownSiblingCannotCompleteSnapshot() = runBlocking {
        response = initial("[$header]", shelf("[${song.replace("\"musicResponsiveListItemRenderer\":", "\"unknownSongRenderer\":{},\"musicResponsiveListItemRenderer\":")} ]"))
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun emptyInitialSectionIsUnknownAndCannotCompleteSnapshot() = runBlocking {
        response = initial("[$header]", "[{},${shelf("[$song]").removeSurrounding("[", "]")}]")
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun initialAppendMembershipBranchCannotBeDroppedBeforeCompletion() = runBlocking {
        response = initial("[$header]", shelf("[$song]"), metadata("section-next")).dropLast(1) +
            ",\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[${song.replace("song-1", "song-2").replace("set-1", "set-2")}]}}]}"
        val first = YouTube.playlist("PLfixture")
        response = sectionResponse("[$playlistCard]")
        assertTrue(first.completed().isFailure)
    }

    @Test fun initialContinuationMembershipBranchMarksSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", shelf("[$song]")).dropLast(1) +
            ",\"continuationContents\":{\"musicPlaylistShelfContinuation\":{\"contents\":[$song]}}}"
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun initialShelfPagingMetadataCannotBeDropped() = runBlocking {
        response = initial("[$header]", shelf("[$song]").replace("\"collapsedItemCount\":0", "\"collapsedItemCount\":0,\"continuations\":${metadata("lost-next")}"))
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun initialShelfUnknownMembershipSiblingMarksSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", shelf("[$song]").replace("\"collapsedItemCount\":0", "\"collapsedItemCount\":0,\"unknownSongShelfRenderer\":{\"contents\":[$song]}"))
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun songContinuationShelfUnknownMembershipSiblingMarksSnapshotIncomplete() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song],"unknownSongShelfRenderer":{"contents":[$song]}}}}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun appendItemsUnknownMembershipSiblingMarksSnapshotIncomplete() = runBlocking {
        response = """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$song],"unknownSongItems":[$song]}}]}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun malformedContinuationContainerCannotDisappearBesideAppendAction() = runBlocking {
        response = """{"responseContext":{},"continuationContents":[$song],"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$song]}}]}"""
        assertTrue(YouTube.playlistContinuation("next").isFailure)
    }

    @Test fun malformedAppendActionCannotDisappearBesideValidAction() = runBlocking {
        response = """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":[$song]},{"appendContinuationItemsAction":{"continuationItems":[$song]}}]}"""
        assertTrue(YouTube.playlistContinuation("next").isFailure)
    }

    @Test fun continuationCommandUnknownSiblingCannotHideAdditionalPaging() = runBlocking {
        val malformedToken = inlineToken("next").replace("\"token\":\"next\"", "\"token\":\"next\",\"unknownContinuationCommand\":{\"token\":\"lost\"}")
        response = initial("[$header]", shelf("[$song,$malformedToken]"))
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun nextContinuationDataUnknownSiblingCannotHideAdditionalPaging() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song],"continuations":[{"nextContinuationData":{"continuation":"next2","unknownContinuationData":{"continuation":"lost"}}}]}}}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun knownNextContinuationTrackingMetadataPreservesNextToken() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song],"trackingParams":"fixture","continuations":[{"nextContinuationData":{"continuation":"next2","clickTrackingParams":"fixture","timeoutMs":0}}]}}}"""
        assertEquals("next2", YouTube.playlistContinuation("next").getOrThrow().continuation)
        assertTrue(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun observedInitialShelfMetadataDoesNotPreventCompletion() = runBlocking {
        val knownShelf = shelf("[$song]").replace("\"collapsedItemCount\":0",
            "\"collapsedItemCount\":0,\"playlistId\":\"PLfixture\",\"header\":{},\"trackingParams\":\"fixture\",\"contentsMultiSelectable\":true,\"targetId\":\"playlist-shelf\"")
        response = initial("[$header]", knownShelf)
        assertEquals(listOf("song-1"), YouTube.playlist("PLfixture").completed().getOrThrow().songs.map { it.id })
    }

    @Test fun knownInlineAndAppendMetadataKeepsSongPagingWorking() = runBlocking {
        val knownInline = inlineToken("songs-next").replace("\"continuationItemRenderer\":{",
            "\"continuationItemRenderer\":{\"trigger\":\"CONTINUATION_TRIGGER_ON_ITEM_SHOWN\",")
            .replace("\"continuationEndpoint\":{", "\"continuationEndpoint\":{\"clickTrackingParams\":\"fixture\",\"commandMetadata\":{},")
            .replace("\"token\":\"songs-next\"", "\"token\":\"songs-next\",\"request\":\"CONTINUATION_REQUEST_TYPE_BROWSE\"")
        response = initial("[$header]", shelf("[$song,$knownInline]"))
        val first = YouTube.playlist("PLfixture")
        response = """{"responseContext":{},"onResponseReceivedActions":[{"clickTrackingParams":"fixture","appendContinuationItemsAction":{"targetId":"playlist-shelf","trackingParams":"fixture","continuationItems":[${song.replace("song-1", "song-2").replace("set-1", "set-2")}]}}]}"""
        assertEquals(listOf("song-1", "song-2"), first.completed().getOrThrow().songs.map { it.id })
    }

    @Test fun competingSectionResponseActionsCannotBeDiscarded() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[$playlistCard]").dropLast(1) + ",\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[$song]}}]}"
        assertTrue(first.completed().isFailure)
    }

    @Test fun competingSectionResponseContentsCannotBeDiscarded() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[$playlistCard]").dropLast(1) + ",\"contents\":{\"sectionListRenderer\":{\"contents\":[$song]}}}"
        assertTrue(first.completed().isFailure)
    }

    @Test fun appendActionUnknownSiblingCannotBeDiscarded() = runBlocking {
        response = """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$song]},"unknownSongAction":{"contents":[$song]}}]}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun unknownActionBeforeAppendCannotBeDiscarded() = runBlocking {
        response = """{"responseContext":{},"onResponseReceivedActions":[{"unknownSongAction":{"contents":[$song]}},{"appendContinuationItemsAction":{"continuationItems":[$song]}}]}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun duplicateInlineSongTokensMarkSnapshotIncomplete() = runBlocking {
        response = initial("[$header]", shelf("[$song,${inlineToken("one")},${inlineToken("two")}]"))
        assertFalse(YouTube.playlist("PLfixture").getOrThrow().snapshotComplete)
    }

    @Test fun conflictingShelfAndInlineTokensMarkContinuationIncomplete() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song,${inlineToken("one")}],"continuations":${metadata("two")}}}}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun shelfTokenWithUnknownMetadataSiblingMarksContinuationIncomplete() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song],"continuations":[{"nextContinuationData":{"continuation":"next2"},"unknownContinuationData":{"continuation":"lost"}}]}}}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun multipleAppendActionsCannotSilentlyDiscardSongRows() = runBlocking {
        response = """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$song]}},{"appendContinuationItemsAction":{"continuationItems":[$song]}}]}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun mixedSongAndSectionContinuationContainersCannotSilentlyDiscardRows() = runBlocking {
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song]},"sectionListContinuation":{"contents":[]}}}"""
        assertFalse(YouTube.playlistContinuation("next").getOrThrow().snapshotComplete)
    }

    @Test fun repeatedSectionTokenFailsWithoutLooping() = runBlocking {
        val first = sectionInitial()
        response = sectionResponse("[$playlistCard]", metadata("section-next"))
        assertTrue(first.completed().isFailure)
    }

    @Test fun repeatedSongTokenFailsWithoutLooping() = runBlocking {
        response = initial("[$header]", shelf("[$song,${inlineToken("songs-next")}]"))
        val first = YouTube.playlist("PLfixture")
        response = """{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song],"continuations":${metadata("songs-next")}}}}"""
        assertTrue(first.completed().isFailure)
    }

    @Test fun sectionTransportFailureDoesNotReturnPartialSuccess() = runBlocking {
        val first = sectionInitial()
        continuationResponse = { throw IOException("Synthetic offline fixture") }
        assertTrue(first.completed().exceptionOrNull() is IOException)
    }

    @Test fun nonRepeatingSectionTokensCannotPaginateForever() = runBlocking {
        val first = sectionInitial()
        var requests = 0
        continuationResponse = {
            requests++
            sectionResponse("[$playlistCard]", if (requests < 1100) metadata("section-$requests") else null)
        }
        assertTrue(first.completed().isFailure)
        assertTrue("Requests must remain bounded", requests in 1..1000)
    }

    @Test fun completedPropagatesCancellation() = runBlocking {
        try {
            Result.failure<com.zionhuang.innertube.pages.PlaylistPage>(CancellationException("Synthetic cancellation")).completed()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // The caller must retain cancellation rather than interpreting it as a failed sync.
        }
    }

    private suspend fun sectionInitial(): Result<com.zionhuang.innertube.pages.PlaylistPage> {
        response = initial("[$header]", shelf("[$song]"), metadata("section-next"))
        return YouTube.playlist("PLfixture")
    }

    private fun metadata(token: String) = """[{"nextContinuationData":{"continuation":"$token"}}]"""
    private fun inlineToken(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

    private fun sectionResponse(rows: String, continuations: String? = null): String {
        val metadata = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """{"responseContext":{},"continuationContents":{"sectionListContinuation":{"contents":[${carousel(rows)}]$metadata}}}"""
    }

    private fun carousel(rows: String) = """{"musicCarouselShelfRenderer":{"contents":$rows,"itemSize":"MUSIC_CAROUSEL_SHELF_ITEM_SIZE_LARGE"}}"""
    private val playlistCard = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Fixture recommendation"}]},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/playlist.jpg","width":120,"height":120}]}}},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLrecommendation","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}}"""

    private fun initial(headers: String, shelves: String, continuations: String? = null): String {
        val sectionContinuations = continuations?.let { ",\"continuations\":$it" }.orEmpty()
        return """{"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":$headers}}}}],"secondaryContents":{"sectionListRenderer":{"contents":$shelves$sectionContinuations}}}}}"""
    }
    private fun shelf(rows: String) = """[{"musicPlaylistShelfRenderer":{"contents":$rows,"collapsedItemCount":0}}]"""

    private val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Fixture playlist"}]},"subtitle":{"runs":[]},"buttons":[]}}"""
    private val song = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Fixture song"}]}}}],"playlistItemData":{"playlistSetVideoId":"set-1","videoId":"song-1"},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/song.jpg","width":120,"height":120}]}}}}}"""
}
