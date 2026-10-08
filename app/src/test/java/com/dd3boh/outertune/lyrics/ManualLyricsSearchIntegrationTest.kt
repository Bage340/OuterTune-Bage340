package com.dd3boh.outertune.lyrics

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.viewmodels.LyricsMenuViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ManualLyricsSearchIntegrationTest {
    private suspend fun withViewModel(
        timeoutMs: Long = 5000,
        search: suspend (String, String, String, Int, (LyricsResult) -> Unit) -> Boolean,
        verify: suspend (LyricsMenuViewModel) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        val store = ViewModelStore()
        try {
            val viewModel = LyricsMenuViewModel(LyricsHelper(context, database), database, search, timeoutMs)
            store.put("lyrics", viewModel)
            verify(viewModel)
        } finally {
            store.clear()
            database.close()
        }
    }

    private suspend fun LyricsMenuViewModel.finishSearch() = withTimeout(5000) {
        viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }

    @Test fun providerFailureIsDistinctFromAbsenceAndRetryClearsFailure() = runBlocking {
        var fail = true
        val provider = object : LyricsProvider {
            override val id = "fake"
            override val name = "Fake"
            override fun isEnabled(context: Context) = true
            override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int, album: String?) =
                if (fail) LyricsFetchResult.Failed(IOException("rate limited")) else LyricsFetchResult.NotFound
        }
        withViewModel(search = { id, title, artist, duration, callback ->
            searchManualLyrics(listOf(provider), id, title, artist, duration, callback, {}) != null
        }) { viewModel ->
            viewModel.search("id", "Song", "Artist", 200)
            viewModel.finishSearch()
            assertTrue(viewModel.searchFailed.value)
            assertTrue(viewModel.results.value.isEmpty())
            assertFalse(viewModel.isLoading.value)
            fail = false
            viewModel.search("id", "Song", "Artist", 200)
            viewModel.finishSearch()
            assertFalse(viewModel.searchFailed.value)
            assertTrue(viewModel.results.value.isEmpty())
        }
    }

    @Test fun timeoutKeepsUsablePartialResultsAndReportsFailure() = runBlocking {
        val partial = LyricsResult("Fallback", "plain lyrics")
        withViewModel(timeoutMs = 100, search = { _, _, _, _, callback ->
            callback(partial)
            awaitCancellation()
        }) { viewModel ->
            viewModel.search("id", "Song", "Artist", 200)
            viewModel.finishSearch()
            assertTrue(viewModel.searchFailed.value)
            assertFalse(viewModel.isLoading.value)
            assertEquals(listOf(partial), viewModel.results.value)
        }
    }

    @Test fun cancelledPreviousSearchCannotPublishOrFinishReplacement() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val current = LyricsResult("Current", "current lyrics")
        withViewModel(search = { id, _, _, _, callback ->
            if (id == "old") {
                oldStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        oldCancelled.complete(Unit)
                        releaseOld.await()
                        callback(LyricsResult("Stale", "stale lyrics"))
                    }
                }
            } else {
                newStarted.complete(Unit)
                releaseNew.await()
                callback(current)
                true
            }
        }) { viewModel ->
            viewModel.search("old", "Song", "Artist", 200)
            withTimeout(5000) { oldStarted.await() }
            viewModel.search("new", "Song", "Artist", 200)
            withTimeout(5000) { newStarted.await(); oldCancelled.await() }
            val jobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toList()
            releaseOld.complete(Unit)
            withTimeout(5000) { jobs.first { it.isCancelled }.join() }
            assertTrue("Old finally must not stop the replacement spinner", viewModel.isLoading.value)
            assertTrue("Stale callback must not publish", viewModel.results.value.isEmpty())
            assertFalse(viewModel.searchFailed.value)
            releaseNew.complete(Unit)
            viewModel.finishSearch()
            assertEquals(listOf(current), viewModel.results.value)
            assertFalse(viewModel.searchFailed.value)
            assertFalse(viewModel.isLoading.value)
        }
    }
}
