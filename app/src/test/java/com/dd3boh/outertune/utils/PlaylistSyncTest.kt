package com.dd3boh.outertune.utils

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.pages.PlaylistPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlaylistSyncTest {
    private lateinit var database: MusicDatabase
    private val date = LocalDateTime.of(2025, 1, 2, 3, 4)

    @Before fun seedCache() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        database.insert(PlaylistEntity(id = "playlist", name = "Cached", browseId = "PLfixture"))
        database.insert(PlaylistEntity(id = "other", name = "Other"))
        database.insert(SongEntity(id = "old", title = "Old", localPath = null, liked = true, likedDate = date, inLibrary = date, dateDownload = date))
        database.insert(PlaylistSongMap(id = 7, playlistId = "playlist", songId = "old", position = 0, setVideoId = "old-set"))
        database.insert(PlaylistSongMap(id = 8, playlistId = "other", songId = "old", position = 0))
    }

    @After fun closeDatabase() { database.close() }

    @Test fun emptySnapshotPreservesExistingLinksAndReportsFailure() = runBlocking {
        val before = database.playlistSongs("playlist").first()
        val success = database.replaceSyncedPlaylist("playlist", snapshot(emptyList()))
        assertEquals(before, database.playlistSongs("playlist").first())
        assertFalse(success)
        assertEquals(1, database.playlistSongs("other").first().size)
    }

    @Test fun nonemptySnapshotCommitsOrderedDuplicatesBeforeReturning() = runBlocking {
        assertTrue(database.replaceSyncedPlaylist("playlist", snapshot(listOf(song("new", "first"), song("old", "second"), song("new", "third")))))
        // No executor drain: success must mean the rows are already committed.
        val rows = database.playlistSongs("playlist").first()
        assertEquals(listOf("new", "old", "new"), rows.map { it.map.songId })
        assertEquals(listOf(0, 1, 2), rows.map { it.map.position })
        assertEquals(listOf("first", "second", "third"), rows.map { it.map.setVideoId })
        val old = database.song("old").first()!!.song
        assertTrue(old.liked)
        assertEquals(date, old.likedDate)
        assertEquals(date, old.inLibrary)
        assertEquals(date, old.dateDownload)
        assertEquals(8, database.playlistSongs("other").first().single().map.id)
    }

    @Test fun failedInsertRollsBackClearingAndAllNewRows() = runBlocking {
        val before = database.playlistSongs("playlist").first()
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_sync BEFORE INSERT ON playlist_song_map WHEN NEW.setVideoId = 'fail' BEGIN SELECT RAISE(ABORT, 'forced sync failure'); END""")
        val success = database.replaceSyncedPlaylist("playlist", snapshot(listOf(song("new", "ok"), song("bad", "fail"))))
        assertFalse(success)
        assertEquals(before, database.playlistSongs("playlist").first())
        assertNull(database.song("new").first())
        assertNull(database.song("bad").first())
    }

    @Test fun explicitlyEmptySnapshotCanInitializeEmptyCache() = runBlocking {
        database.clearPlaylist("playlist")
        assertTrue(database.replaceSyncedPlaylist("playlist", snapshot(emptyList())))
        assertTrue(database.playlistSongs("playlist").first().isEmpty())
    }

    private fun song(id: String, setId: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "https://example.invalid/song.jpg", setVideoId = setId)

    @Test fun incompleteSnapshotPreservesAll99CachedLinks() = runBlocking {
        seed99Links()
        val before = database.playlistSongs("playlist").first()
        assertFalse(database.replaceSyncedPlaylist("playlist", snapshot(listOf(song("partial", "partial-set")), complete = false)))
        assertEquals(before, database.playlistSongs("playlist").first())
        assertNull(database.song("partial").first())
    }

    @Test fun emptySnapshotPreservesAll99CachedLinks() = runBlocking {
        seed99Links()
        val before = database.playlistSongs("playlist").first()
        assertFalse(database.replaceSyncedPlaylist("playlist", snapshot(emptyList())))
        assertEquals(before, database.playlistSongs("playlist").first())
    }

    @Test fun unfetchedContinuationPreservesCachedLinks() = runBlocking {
        val before = database.playlistSongs("playlist").first()
        val page = snapshot(listOf(song("partial", "partial-set"))).copy(songsContinuation = "next")
        assertFalse(database.replaceSyncedPlaylist("playlist", page))
        assertEquals(before, database.playlistSongs("playlist").first())
        assertNull(database.song("partial").first())
    }

    @Test fun unconsumedSectionContinuationPreservesCachedLinks() = runBlocking {
        val before = database.playlistSongs("playlist").first()
        val page = snapshot(listOf(song("partial", "partial-set"))).copy(continuation = "section-next")
        assertFalse(database.replaceSyncedPlaylist("playlist", page))
        assertEquals(before, database.playlistSongs("playlist").first())
        assertNull(database.song("partial").first())
    }

    @Test fun successWaitsForTransactionExecutorAndCommit() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        database.transaction {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) {
            started.complete(Unit)
            database.replaceSyncedPlaylist("playlist", snapshot(listOf(song("new", "new-set"))))
        }
        try {
            started.await()
            assertNull(withTimeoutOrNull(100) { pending.await() })
        } finally {
            release.countDown()
        }
        assertTrue(pending.await())
        assertEquals(listOf("new"), database.playlistSongs("playlist").first().map { it.map.songId })
    }

    private fun snapshot(songs: List<SongItem>, complete: Boolean = true) = PlaylistPage(
        playlist = PlaylistItem("PLfixture", "Fixture", null, null, null, null, null, null),
        songs = songs, songsContinuation = null, continuation = null, snapshotComplete = complete,
    )

    private fun seed99Links() {
        database.clearPlaylist("playlist")
        repeat(99) { position ->
            val id = "cached-$position"
            database.insert(SongEntity(id = id, title = id, localPath = null))
            database.insert(PlaylistSongMap(playlistId = "playlist", songId = id, position = position, setVideoId = "set-$position"))
        }
    }

    @Test fun cancellationPropagatesWithoutWritingOrReportingSuccess() {
        var cancellationObserved = false
        val failure = runCatching {
            runBlocking {
                coroutineContext[Job]!!.cancel()
                try {
                    database.replaceSyncedPlaylist("playlist", snapshot(listOf(song("new", "new-set"))))
                } catch (exception: CancellationException) {
                    cancellationObserved = true
                    throw exception
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(cancellationObserved)
        runBlocking {
            assertEquals(listOf("old"), database.playlistSongs("playlist").first().map { it.map.songId })
            assertNull(database.song("new").first())
        }
    }
}
