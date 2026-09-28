package com.dd3boh.outertune.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlaylistLibraryTest {
    private lateinit var database: MusicDatabase
    private val addedAt = LocalDateTime.of(2026, 9, 27, 12, 0)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(
            Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        )
        database.insert(PlaylistEntity(id = "local-playlist", name = "Test", isLocal = true))
    }

    @After fun tearDown() = database.close()

    @Test fun emptyPlaylistChangesNothing() {
        assertEquals(PlaylistLibraryResult(0, 0), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
    }

    @Test fun oneLocalSongBecomesLibrarySongWithoutChangingIdentityOrPath() {
        addSong("local", localPath = "/music/song.mp3")

        assertEquals(PlaylistLibraryResult(1, 0), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
        val song = songEntity("local")!!
        assertEquals("local", song.id)
        assertEquals("/music/song.mp3", song.localPath)
        assertEquals(addedAt, song.inLibrary)
        assertEquals(1, songRowCount())
    }

    @Test fun fiveHundredSongsAreUpdatedInOneOperation() {
        repeat(500) { addSong("remote-$it") }

        assertEquals(PlaylistLibraryResult(500, 0), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
        assertEquals(500, librarySongCount())
        assertEquals(500, songRowCount())
    }

    @Test fun existingLibrarySongsKeepTheirOriginalDateAndAreCounted() {
        val originalDate = addedAt.minusDays(3)
        addSong("already", inLibrary = originalDate)
        addSong("new", localPath = "/music/new.mp3")

        assertEquals(PlaylistLibraryResult(1, 1), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
        assertEquals(originalDate, songEntity("already")!!.inLibrary)
        assertEquals(addedAt, songEntity("new")!!.inLibrary)
    }

    @Test fun mixedLocalDownloadedAndRemoteSongsPreserveTheirRows() {
        addSong("local", localPath = "/music/local.mp3")
        addSong("downloaded", downloaded = true)
        addSong("remote")

        assertEquals(PlaylistLibraryResult(3, 0), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
        assertEquals(3, songRowCount())
        assertEquals("/music/local.mp3", songEntity("local")!!.localPath)
        assertNotNull(songEntity("downloaded")!!.dateDownload)
        assertNull(songEntity("remote")!!.localPath)
    }

    @Test fun duplicateEntriesAndRepeatedPressesDoNotAddTwice() {
        addSong("repeated")
        database.insert(PlaylistSongMap(playlistId = "local-playlist", songId = "repeated", position = 1))

        assertEquals(PlaylistLibraryResult(1, 0), database.addPlaylistSongsToLibrary("local-playlist", addedAt))
        assertEquals(PlaylistLibraryResult(0, 1), database.addPlaylistSongsToLibrary("local-playlist", addedAt.plusDays(1)))
        assertEquals(addedAt, songEntity("repeated")!!.inLibrary)
        assertEquals(1, songRowCount())
    }

    @Test fun concurrentRequestsCountTheSameSongOnlyOnce() = runBlocking {
        addSong("shared")
        val start = CompletableDeferred<Unit>()
        val requests = listOf(
            async(Dispatchers.IO) { start.await(); database.addPlaylistSongsToLibrary("local-playlist", addedAt) },
            async(Dispatchers.IO) { start.await(); database.addPlaylistSongsToLibrary("local-playlist", addedAt) }
        )

        start.complete(Unit)

        assertEquals(
            listOf(PlaylistLibraryResult(0, 1), PlaylistLibraryResult(1, 0)),
            requests.awaitAll().sortedBy { it.added }
        )
        assertEquals(1, librarySongCount())
        assertEquals(1, songRowCount())
    }

    @Test fun databaseFailureRollsBackEverySongUpdate() {
        addSong("first")
        addSong("second")
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_second_library_update BEFORE UPDATE OF inLibrary ON song
               WHEN NEW.id = 'second' BEGIN SELECT RAISE(ABORT, 'forced failure'); END"""
        )

        val failure = runCatching { database.addPlaylistSongsToLibrary("local-playlist", addedAt) }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(songEntity("first")!!.inLibrary)
        assertNull(songEntity("second")!!.inLibrary)
    }

    private fun addSong(id: String, localPath: String? = null, downloaded: Boolean = false,
                        inLibrary: LocalDateTime? = null) {
        database.insert(
            SongEntity(id = id, title = id, isLocal = localPath != null, localPath = localPath,
                dateDownload = if (downloaded) addedAt.minusDays(1) else null,
                inLibrary = inLibrary)
        )
        val position = playlistSongCount("local-playlist")
        database.insert(PlaylistSongMap(playlistId = "local-playlist", songId = id, position = position))
    }

    private fun songRowCount(): Int = count("SELECT COUNT(*) FROM song")
    private fun librarySongCount(): Int = count("SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL")
    private fun playlistSongCount(id: String): Int = count("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = '$id'")

    private fun count(sql: String): Int = database.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }

    private fun songEntity(id: String): SongEntity? = runBlocking { database.song(id).first()?.song }
}
