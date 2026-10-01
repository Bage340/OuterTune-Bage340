package com.dd3boh.outertune.lyrics

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.constants.EnableSimpMusicKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.viewmodels.LyricsMenuViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LyricsRefreshIntegrationTest {
    @Test
    fun manualRefreshRunsOneFetchAndPublishesFailureWithoutNegativeCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.dataStore.edit {
            it[EnableLrcLibKey] = false
            it[EnableKugouKey] = false
            it[EnableSimpMusicKey] = false
            it[EnableBetterLyricsKey] = false
        }
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        val store = ViewModelStore()
        try {
            val helper = LyricsHelper(context, database)
            val viewModel = LyricsMenuViewModel(helper, database)
            store.put("lyrics", viewModel)
            // A local path excludes ID-only providers; disabling title providers gives a deterministic
            // transient/unavailable lookup without making real network requests.
            val song = MediaMetadata("local", "Local", emptyList(), 120, genre = null, isLocal = true)
            var starts = 0
            val observer = launch(Dispatchers.Unconfined) {
                helper.fetchStates.collect { if (it[song.id] == LyricsFetchStatus.LOADING) starts++ }
            }
            viewModel.refetchLyrics(song)
            withTimeout(5000) { viewModel.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            observer.cancel()
            assertEquals("Refresh must not perform a second lookup through getLyrics", 1, starts)
            assertEquals(LyricsFetchStatus.FAILED, helper.fetchStates.value[song.id])
            assertNull(database.lyrics(song.id).first())
            helper.fetchAndStoreRemote(song.copy(id = "prefetch"), LyricsFetchRole.PREFETCH)
            assertFalse("Background prefetch must not control the visible loading state", helper.fetchStates.value.containsKey("prefetch"))
        } finally {
            store.clear()
            database.close()
        }
    }
}
