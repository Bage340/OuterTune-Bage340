package com.dd3boh.outertune.viewmodels

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.YouTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OnlinePlaylistContinuationTest {
    private lateinit var database: MusicDatabase
    private lateinit var innerTube: InnerTube
    private lateinit var originalClient: HttpClient
    private lateinit var fixtureClient: HttpClient
    private var viewModel: OnlinePlaylistViewModel? = null
    private val requests = AtomicInteger()
    @Volatile private var continuationResponse = "{\"responseContext\":{}}"

    @Before fun setUp() {
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), InternalDatabase::class.java).allowMainThreadQueries().build())
        innerTube = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(YouTube) as InnerTube
        val field = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        originalClient = field.get(innerTube) as HttpClient
        fixtureClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            engine {
                addInterceptor { chain ->
                    val fixture = if (requests.incrementAndGet() == 1) initial else continuationResponse
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Fixture").header("Content-Type", "application/json")
                        .body(fixture.toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        field.set(innerTube, fixtureClient)
    }

    @After fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }.set(innerTube, originalClient)
        fixtureClient.close()
        database.close()
    }

    @Test fun failedBulkContinuationStopsAndKeepsSongsAndTokenForManualRetry() = runBlocking {
        val model = OnlinePlaylistViewModel(SavedStateHandle(mapOf("playlistId" to "PLfixture")), database).also { viewModel = it }
        withTimeout(5_000) { model.playlistSongs.filter { it.isNotEmpty() }.first() }
        withTimeout(5_000) { while (model.isLoading.value) delay(10) }
        model.loadRemainingSongs()
        withTimeout(1_000) { while (requests.get() < 2 || model.isLoading.value) delay(10) }
        assertEquals(2, requests.get())
        assertEquals(listOf("song-1"), model.playlistSongs.value.map { it.id })
        assertEquals("next", model.continuation)
        assertFalse(model.isLoading.value)

        continuationResponse = """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${song.replace("song-1", "song-2")}]}}]}"""
        model.getContinuation("next")
        assertEquals(3, requests.get())
        assertEquals(listOf("song-1", "song-2"), model.playlistSongs.value.map { it.id })
        assertNull(model.continuation)
    }

    private val song = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Fixture song"}]}}}],"playlistItemData":{"playlistSetVideoId":"set-1","videoId":"song-1"},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/song.jpg","width":120,"height":120}]}}}}}"""
    private val initial = """{"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Fixture"}]},"subtitle":{"runs":[]},"buttons":[]}}]}}}}],"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"collapsedItemCount":0,"contents":[$song,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}}]}}}}}"""
}
