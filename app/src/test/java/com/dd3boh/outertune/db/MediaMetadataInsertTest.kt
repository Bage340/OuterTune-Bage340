package com.dd3boh.outertune.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaMetadataInsertTest {
    private lateinit var database: MusicDatabase

    @Before
    fun setUp() {
        database = MusicDatabase(
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun newLocalAlbumUsesTheSameIdentityForSongAndRelation() = runBlocking {
        database.insert(metadata("first", album = MediaMetadata.Album("LBscan-first", "Tagged Album", isLocal = true)))

        val stored = database.allLocalDbSongs().single()
        val album = requireNotNull(stored.album)
        assertTrue(album.id.startsWith("LB"))
        assertEquals(album.id, stored.song.albumId)
        assertEquals("Tagged Album", stored.song.albumName)
        assertEquals("Tagged Album", album.title)
        assertEquals(listOf(stored.id), database.albumSongs(album.id).first().map { it.id })
        assertNotNull(database.album(stored.song.albumId!!).first())
    }

    @Test
    fun existingAlbumIsReusedForBothSongAndRelation() = runBlocking {
        val existing = AlbumEntity("LBexisting", title = "Tagged Album", songCount = 1, duration = 30, isLocal = true)
        database.insert(existing)
        database.insert(metadata("next", album = MediaMetadata.Album("LBscan-next", "Tagged Album", isLocal = true)))

        val stored = database.allLocalDbSongs().single()
        assertEquals("LBexisting", stored.song.albumId)
        assertEquals("LBexisting", stored.album?.id)
        assertEquals(listOf("LBexisting"), database.allLocalAlbumsByName().map { it.id })
        assertEquals(listOf("next"), database.albumSongs("LBexisting").first().map { it.id })
    }

    @Test
    fun songWithoutAnAlbumDoesNotCreateAnAlbumRelation() = runBlocking {
        database.insert(metadata("no-album", album = null))

        val stored = database.allLocalDbSongs().single()
        assertNull(stored.song.albumId)
        assertNull(stored.song.albumName)
        assertNull(stored.album)
        assertTrue(database.allLocalAlbumsByName().isEmpty())
    }

    @Test
    fun duplicateSongInsertDoesNotChangeItsAlbumOrCreateAnotherOne() = runBlocking {
        database.insert(metadata("same", album = MediaMetadata.Album("LBfirst", "First Album", isLocal = true)))
        val before = database.allLocalDbSongs().single()
        val albumsBefore = database.allLocalAlbumsByName()

        database.insert(metadata("same", album = MediaMetadata.Album("LBsecond", "Second Album", isLocal = true)))

        assertEquals(before, database.allLocalDbSongs().single())
        assertEquals(albumsBefore, database.allLocalAlbumsByName())
    }

    @Test
    fun remoteSongRetainsItsSuppliedAlbumIdentityAndCallerChanges() = runBlocking {
        val addedAt = LocalDateTime.of(2026, 1, 2, 3, 4)
        val remote = metadata("remote", album = MediaMetadata.Album("MPREremote", "Remote Album"), isLocal = false)
        database.insert(remote) { it.copy(inLibrary = addedAt, liked = true) }

        val stored = requireNotNull(database.song("remote").first()).song
        assertEquals(remote.toSongEntity().copy(inLibrary = addedAt, liked = true), stored)
    }

    private fun metadata(id: String, album: MediaMetadata.Album?, isLocal: Boolean = true) = MediaMetadata(
        id = id,
        title = "Song $id",
        artists = emptyList(),
        duration = 30,
        album = album,
        genre = null,
        isLocal = isLocal,
        localPath = if (isLocal) "/music/$id.flac" else null,
    )
}
