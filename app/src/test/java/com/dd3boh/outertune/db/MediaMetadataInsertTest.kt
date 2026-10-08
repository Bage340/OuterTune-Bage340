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
        assertEquals("MPREremote", requireNotNull(database.song("remote").first()).album?.id)
        assertEquals(listOf("remote"), database.albumSongs("MPREremote").first().map { it.id })
    }

    @Test
    fun localAlbumWithRemoteTitleDoesNotOverwriteRemoteAlbum() = runBlocking {
        val remote = savedAlbum("MPREsaved", isLocal = false)
        database.insert(remote)

        database.insert(metadata("local", album = MediaMetadata.Album("LBscan", remote.title, isLocal = true)))

        assertEquals(remote, database.albumById(remote.id))
        val local = database.allLocalDbSongs().single()
        assertTrue(requireNotNull(local.album).isLocal)
        assertTrue(local.song.albumId != remote.id)
        assertEquals(local.song.albumId, local.album?.id)
        assertEquals(listOf("local"), database.albumSongs(local.song.albumId!!).first().map { it.id })
        assertTrue(database.albumSongs(remote.id).first().isEmpty())
    }

    @Test
    fun addingSongToExistingLocalAlbumPreservesSavedAlbumState() = runBlocking {
        val existing = savedAlbum("LBsaved", isLocal = true)
        database.insert(existing)

        database.insert(metadata("local", album = MediaMetadata.Album("LBscan", existing.title, isLocal = true)))

        val stored = requireNotNull(database.albumById(existing.id))
        assertEquals(existing.bookmarkedAt, stored.bookmarkedAt)
        assertEquals(existing.playlistId, stored.playlistId)
        assertEquals(existing.year, stored.year)
        assertEquals(existing.themeColor, stored.themeColor)
        assertEquals(existing.thumbnailUrl, stored.thumbnailUrl)
        assertTrue(stored.isLocal)
        assertEquals(existing.id, database.allLocalDbSongs().single().song.albumId)
    }

    @Test
    fun remoteAlbumUsesItsIdDespiteAnotherAlbumWithTheSameTitle() = runBlocking {
        val other = savedAlbum("MPREother", isLocal = false)
        val existing = savedAlbum("MPREincoming", isLocal = false)
        database.insert(other)
        database.insert(existing)

        database.insert(metadata("remote", album = MediaMetadata.Album(existing.id, existing.title), isLocal = false))

        assertEquals(other, database.albumById(other.id))
        val stored = requireNotNull(database.albumById(existing.id))
        assertEquals(existing.bookmarkedAt, stored.bookmarkedAt)
        assertEquals(existing.playlistId, stored.playlistId)
        assertEquals(existing.year, stored.year)
        assertEquals(existing.themeColor, stored.themeColor)
        assertEquals(existing.thumbnailUrl, stored.thumbnailUrl)
        val remote = requireNotNull(database.song("remote").first())
        assertEquals(existing.id, remote.song.albumId)
        assertEquals(existing.id, remote.album?.id)
        assertEquals(listOf("remote"), database.albumSongs(existing.id).first().map { it.id })
        assertTrue(database.albumSongs(other.id).first().isEmpty())
        assertEquals(existing, stored)
    }

    @Test
    fun localSongCanRetainAnExplicitRemoteAlbumLink() = runBlocking {
        val remote = savedAlbum("MPREsaved", isLocal = false)
        val local = savedAlbum("LBsaved", isLocal = true)
        database.insert(local)
        database.insert(remote)

        database.insert(metadata("local", album = MediaMetadata.Album(remote.id, remote.title)))

        val stored = database.allLocalDbSongs().single()
        assertEquals(remote.id, stored.song.albumId)
        assertEquals(remote.id, stored.album?.id)
        assertEquals(remote, database.albumById(remote.id))
        assertEquals(local, database.albumById(local.id))
    }

    @Test
    fun localAlbumTotalsAndOrderReflectDistinctInsertedSongs() = runBlocking {
        val album = MediaMetadata.Album("LBscan", "Local Album", isLocal = true)
        listOf("first", "second", "third").forEach { database.insert(metadata(it, album)) }
        database.insert(metadata("second", album).copy(duration = 500))

        val stored = database.allLocalAlbumsByName().single()
        assertEquals(3, stored.songCount)
        assertEquals(90, stored.duration)
        database.openHelper.readableDatabase.query(
            "SELECT songId, `index` FROM song_album_map WHERE albumId = ? ORDER BY `index`",
            arrayOf(stored.id),
        ).use { cursor ->
            val order = mutableListOf<Pair<String, Int>>()
            while (cursor.moveToNext()) order.add(cursor.getString(0) to cursor.getInt(1))
            assertEquals(listOf("first" to 0, "second" to 1, "third" to 2), order)
        }
    }

    private fun savedAlbum(id: String, isLocal: Boolean) = AlbumEntity(
        id = id,
        playlistId = "saved-playlist",
        title = "Shared Album",
        year = 2001,
        thumbnailUrl = "saved-artwork",
        themeColor = 123,
        songCount = 2,
        duration = 60,
        lastUpdateTime = LocalDateTime.of(2026, 1, 2, 3, 4),
        bookmarkedAt = LocalDateTime.of(2026, 1, 2, 3, 4),
        isLocal = isLocal,
    )

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
