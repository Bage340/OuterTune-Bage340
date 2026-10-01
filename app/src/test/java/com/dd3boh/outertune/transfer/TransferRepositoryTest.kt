package com.dd3boh.outertune.transfer

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TransferRepositoryTest {
    private lateinit var database: MusicDatabase
    private lateinit var repository: TransferRepository
    private val remote = TransferTrack(TrackSource.YOUTUBE, "AbCdEf12345", "Remote", listOf("One", "Two"), "Album", 120, liked = true, inLibrary = true)
    private val local = TransferTrack(TrackSource.LOCAL, "LSimport", "Local", listOf("Artist"), null, 50, "/music/local.mp3", inLibrary = true)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        repository = TransferRepository(database, LocalReferenceAccess { it == "/music/local.mp3" })
    }

    @After fun tearDown() = database.close()

    @Test fun importsOfflineRemoteAndLocalMetadataThenExportsOrderedPlaylist() = runBlocking {
        val document = TransferDocument(listOf(remote, local), listOf(TransferPlaylist("p1", "Mix", listOf(local, remote))))
        val preview = repository.prepareImport(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
        assertEquals(0, preview.staged.unresolvedCount)
        val result = repository.commitImport(preview)
        assertEquals(2, result.createdSongs)
        assertEquals(1, result.createdPlaylists)
        assertEquals(2, result.addedPlaylistEntries)
        assertEquals(0, result.skippedUnresolved)
        assertEquals("Remote", database.song(remote.stableId).first()?.song?.title)
        assertEquals(listOf("One", "Two"), database.transferArtistNames(listOf(remote.stableId)).map { it.name })
        assertEquals(120, database.song(remote.stableId).first()?.song?.duration)
        assertEquals("Album", database.song(remote.stableId).first()?.song?.albumName)
        val exported = TransferCodec.decode(TransferFormat.JSON, repository.export(TransferFormat.JSON, listOf("p1")))
        assertEquals(document, exported)
    }

    @Test fun missingLocalIsSkippedAndExistingFlagsAreNeverDowngraded() = runBlocking {
        repository = TransferRepository(database, LocalReferenceAccess { false })
        val originalDate = LocalDateTime.of(2026, 9, 1, 12, 0)
        database.insert(SongEntity(id = remote.stableId, title = "Existing", localPath = null, liked = true, inLibrary = originalDate))
        val incoming = TransferDocument(listOf(remote.copy(liked = false, inLibrary = false), local),
            listOf(TransferPlaylist("p2", "Mix", listOf(remote.copy(liked = false, inLibrary = false), local, remote))))
        val result = repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, incoming))
        assertEquals(0, result.createdSongs)
        assertEquals(2, result.skippedUnresolved)
        assertEquals(1, result.addedPlaylistEntries)
        assertEquals(true, database.song(remote.stableId).first()?.song?.liked)
        assertEquals(originalDate, database.song(remote.stableId).first()?.song?.inLibrary)
        assertNull(database.song(local.stableId).first())
        val maps = database.songMapsToPlaylist("p2", 0)
        assertEquals(listOf(remote.stableId), maps.map { it.songId })
    }

    @Test fun malformedImportAndDatabaseFailureLeaveNoPartialWrites() = runBlocking {
        assertTrue(runCatching { repository.import(TransferFormat.JSON, "{".toByteArray()) }.exceptionOrNull() is TransferException)
        assertEquals(0, count("SELECT COUNT(*) FROM song"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_second_map BEFORE INSERT ON playlist_song_map WHEN NEW.songId = 'ZyXwVu98765' BEGIN SELECT RAISE(ABORT, 'forced failure'); END"
        )
        val second = remote.copy(stableId = "ZyXwVu98765", title = "Second")
        val document = TransferDocument(emptyList(), listOf(TransferPlaylist("rollback", "Rollback", listOf(remote, second))))
        assertNotNull(runCatching { repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document)) }.exceptionOrNull())
        assertEquals(0, count("SELECT COUNT(*) FROM song"))
        assertEquals(0, count("SELECT COUNT(*) FROM playlist"))
        assertEquals(0, count("SELECT COUNT(*) FROM playlist_song_map"))
    }

    @Test fun matchesLocalByExactPathAndNeverMergesRemoteByTitle() = runBlocking {
        database.insert(SongEntity(id = "LSactual", title = "Local", isLocal = true, localPath = local.localUri))
        val sameTitleNewVideo = remote.copy(stableId = "ZyXwVu98765", title = "Local", artists = emptyList(), liked = false, inLibrary = false)
        val document = TransferDocument(listOf(local, sameTitleNewVideo), emptyList())
        val result = repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
        assertEquals(1, result.createdSongs)
        assertEquals(1, result.reusedSongs)
        assertEquals(2, count("SELECT COUNT(*) FROM song"))
        assertEquals("LSactual", database.transferLocalSongEntities(listOf(local.localUri!!)).single().id)
        assertNotNull(database.song(sameTitleNewVideo.stableId).first())
    }

    @Test fun likedOnlySongRetainsSeparateLibraryFlagAcrossImportAndExport() = runBlocking {
        val likedOnly = remote.copy(inLibrary = false)
        val document = TransferDocument(listOf(likedOnly), emptyList())
        repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
        val stored = database.song(likedOnly.stableId).first()!!.song
        assertTrue(stored.liked)
        assertNull(stored.inLibrary)
        val exported = TransferCodec.decode(TransferFormat.JSON, repository.export(TransferFormat.JSON, emptyList()))
        assertEquals(document, exported)
    }

    @Test fun repeatedImportDoesNotDuplicateSongsOrPlaylistMembership() = runBlocking {
        val document = TransferDocument(listOf(remote), listOf(TransferPlaylist("repeat", "Repeat", listOf(remote, remote))))
        val bytes = TransferCodec.encode(TransferFormat.JSON, document)
        repository.import(TransferFormat.JSON, bytes)
        val result = repository.import(TransferFormat.JSON, bytes)
        assertEquals(0, result.createdSongs)
        assertEquals(0, result.createdPlaylists)
        assertEquals(0, result.addedPlaylistEntries)
        assertEquals(1, count("SELECT COUNT(*) FROM song"))
        assertEquals(1, count("SELECT COUNT(*) FROM playlist_song_map"))
    }

    @Test fun bulkImportPreservesCountsAndPositionForHundredAndThousandTracks() = runBlocking {
        for (size in listOf(100, 1000)) {
            val tracks = (0 until size).map { remote.copy(stableId = "%011d".format(it), title = "Track $it", artists = emptyList(), liked = false, inLibrary = false) }
            val document = TransferDocument(emptyList(), listOf(TransferPlaylist("bulk-$size", "Bulk $size", tracks)))
            val result = repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
            assertEquals(size - if (size == 1000) 100 else 0, result.createdSongs)
            assertEquals(size, result.addedPlaylistEntries)
            val positions = database.songMapsToPlaylist("bulk-$size", 0).map { it.position }
            assertEquals((0 until size).toList(), positions)
        }
    }

    @Test fun bareM3uYouTubeUrlResolvesMetadataBeforePreviewAndCommit() = runBlocking {
        repository = TransferRepository(database, LocalReferenceAccess { false }, RemoteTrackMetadataResolver { id ->
            assertEquals(remote.stableId, id)
            remote.copy(liked = false, inLibrary = false)
        })
        val m3u = "#EXTM3U\nhttps://www.youtube.com/watch?v=${remote.stableId}\n".toByteArray()
        val preview = repository.prepareImport(TransferFormat.M3U8, m3u)
        val track = preview.document.playlists.single().tracks.single()
        assertEquals("Remote", track.title)
        assertEquals(listOf("One", "Two"), track.artists)
        assertEquals("Album", track.album)
        assertEquals(120, track.durationSeconds)
        repository.commitImport(preview)
        assertEquals("Remote", database.song(remote.stableId).first()?.song?.title)
        assertEquals(listOf("One", "Two"), database.transferArtistNames(listOf(remote.stableId)).map { it.name })
    }

    @Test fun failedM3uMetadataLookupKeepsImportUsableOffline() = runBlocking {
        repository = TransferRepository(database, LocalReferenceAccess { false }, RemoteTrackMetadataResolver {
            throw java.io.IOException("offline")
        })
        val m3u = "#EXTM3U\nhttps://www.youtube.com/watch?v=${remote.stableId}\n".toByteArray()
        val preview = repository.prepareImport(TransferFormat.M3U8, m3u)
        assertEquals(remote.stableId, preview.document.playlists.single().tracks.single().title)
        assertEquals(1, repository.commitImport(preview).createdSongs)
        assertEquals(remote.stableId, database.song(remote.stableId).first()?.song?.title)
    }

    @Test fun fullLibraryReimportPreservesBookmarkedOnlinePlaylistAndMembership() = runBlocking {
        val bookmarked = LocalDateTime.of(2026, 9, 1, 12, 0)
        val online = PlaylistEntity(
            id = "online-row", name = "Online mix", browseId = "VLonline123", isLocal = false,
            bookmarkedAt = bookmarked, thumbnailUrl = "https://example.com/art.jpg", remoteSongCount = 1,
        )
        database.insert(online)
        database.insert(SongEntity(id = remote.stableId, title = remote.title, localPath = null))
        database.insert(PlaylistSongMap(playlistId = online.id, songId = remote.stableId, position = 0, setVideoId = "set-video"))
        val exported = repository.export(TransferFormat.JSON)
        val result = repository.import(TransferFormat.JSON, exported)
        assertEquals(0, result.createdPlaylists)
        assertEquals(1, result.reusedPlaylists)
        assertEquals(0, result.addedPlaylistEntries)
        assertEquals(online, database.playlistEntities().single { it.id == online.id })
        assertEquals("set-video", database.songMapsToPlaylist(online.id, 0).single().setVideoId)
    }

    @Test fun bookmarkedOnlinePlaylistImportsAsLocalSnapshotInCleanDatabase() = runBlocking {
        val online = PlaylistEntity(id = "online-row", name = "Online mix", browseId = "VLonline123",
            isLocal = false, bookmarkedAt = LocalDateTime.of(2026, 9, 1, 12, 0))
        database.insert(online)
        database.insert(SongEntity(id = remote.stableId, title = remote.title, localPath = null))
        database.insert(PlaylistSongMap(playlistId = online.id, songId = remote.stableId, position = 0))
        val exported = repository.export(TransferFormat.JSON)
        database.close()
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        repository = TransferRepository(database, LocalReferenceAccess { false })
        val result = repository.import(TransferFormat.JSON, exported)
        assertEquals(1, result.createdPlaylists)
        val restored = database.playlistEntities().single()
        assertEquals(online.id, restored.id)
        assertNull(restored.browseId)
        assertTrue(restored.isLocal)
        assertNotNull(restored.bookmarkedAt)
        assertEquals(listOf(remote.stableId), database.songMapsToPlaylist(restored.id, 0).map { it.songId })
        val repeat = repository.import(TransferFormat.JSON, exported)
        assertEquals(0, repeat.createdPlaylists)
        assertEquals(0, repeat.addedPlaylistEntries)
    }

    @Test fun forgedRemotePlaylistCannotCreateOrModifyAccountLinkedRow() = runBlocking {
        val trusted = PlaylistEntity(id = "trusted", name = "My playlist", browseId = "VLprivate123", isLocal = false)
        database.insert(trusted)
        val forged = TransferPlaylist("attacker-row", "Imported copy", listOf(remote), isLocal = false,
            browseId = trusted.browseId, bookmarked = true)
        repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON,
            TransferDocument(emptyList(), listOf(forged))))
        assertEquals(trusted, database.playlistEntities().single { it.id == trusted.id })
        assertTrue(database.songMapsToPlaylist(trusted.id, 0).isEmpty())
        val imported = database.playlistEntities().single { it.id == forged.stableId }
        assertTrue(imported.isLocal)
        assertNull(imported.browseId)
    }

    @Test fun localImportReusesSameFileUnderNormalizedAccessiblePath() = runBlocking {
        database.insert(SongEntity(id = "LSactual", title = "Existing", isLocal = true, localPath = "/music/local.mp3"))
        repository = TransferRepository(database, LocalReferenceAccess { true })
        val movedSpelling = local.copy(stableId = "LSother", localUri = "/music/./local.mp3")
        val result = repository.import(TransferFormat.JSON,
            TransferCodec.encode(TransferFormat.JSON, TransferDocument(listOf(movedSpelling), emptyList())))
        assertEquals(0, result.createdSongs)
        assertEquals(1, result.reusedSongs)
        assertEquals(1, count("SELECT COUNT(*) FROM song"))
        assertEquals("Existing", database.song("LSactual").first()?.song?.title)
    }

    @Test fun conflictingLocalStableIdDoesNotMergeDifferentAccessiblePaths() = runBlocking {
        database.insert(SongEntity(id = "LSactual", title = "Existing", isLocal = true, localPath = "/music/one.mp3"))
        repository = TransferRepository(database, LocalReferenceAccess { true })
        val incoming = local.copy(stableId = "LSactual", localUri = "/music/two.mp3")
        val result = repository.import(TransferFormat.JSON,
            TransferCodec.encode(TransferFormat.JSON, TransferDocument(listOf(incoming), emptyList())))
        assertEquals(1, result.createdSongs)
        assertEquals(2, count("SELECT COUNT(*) FROM song"))
        assertEquals("/music/one.mp3", database.song("LSactual").first()?.song?.localPath)
        assertEquals("/music/two.mp3", database.transferLocalSongEntities(listOf("/music/two.mp3")).single().localPath)
    }

    @Test fun equivalentLocalPathsInOneImportCreateOnlyOneSongAndPlaylistEntry() = runBlocking {
        repository = TransferRepository(database, LocalReferenceAccess { true })
        val alias = local.copy(stableId = "LSalias", localUri = "/music/./local.mp3")
        val localhostAlias = local.copy(stableId = "LShostAlias", localUri = "file://localhost/music/local.mp3")
        val document = TransferDocument(emptyList(), listOf(TransferPlaylist("aliases", "Aliases", listOf(local, alias, localhostAlias))))
        val result = repository.import(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
        assertEquals(1, result.createdSongs)
        assertEquals(1, result.addedPlaylistEntries)
        assertEquals(2, result.skippedDuplicateEntries)
        assertEquals(1, count("SELECT COUNT(*) FROM song"))
        assertEquals(listOf(local.stableId), database.songMapsToPlaylist("aliases", 0).map { it.songId })
    }

    @Test fun excessivelyNestedJsonDoesNotWriteDatabase() = runBlocking {
        val nested = "[".repeat(80) + "0" + "]".repeat(80)
        val malicious = "{\"schemaVersion\":1,\"library\":[],\"playlists\":[],\"extra\":$nested}"
        assertTrue(runCatching { repository.import(TransferFormat.JSON, malicious.toByteArray()) }.exceptionOrNull() is TransferException)
        assertEquals(0, count("SELECT COUNT(*) FROM song"))
        assertEquals(0, count("SELECT COUNT(*) FROM playlist"))
    }

    @Test fun sharedArtistsAreLookedUpOncePerImportAndKeepTheirOrder() = runBlocking {
        val lookups = AtomicInteger()
        recreateDatabaseWithQueryCallback { sql ->
            if (sql.contains("FROM artist", ignoreCase = true) && sql.contains("WHERE name", ignoreCase = true)) {
                lookups.incrementAndGet()
            }
        }
        val tracks = (0 until 100).map { remote.copy(stableId = "%011d".format(it)) }
        val result = repository.import(TransferFormat.JSON,
            TransferCodec.encode(TransferFormat.JSON, TransferDocument(tracks, emptyList())))
        assertEquals(100, result.createdSongs)
        assertEquals(2, lookups.get())
        assertEquals(2, count("SELECT COUNT(*) FROM artist"))
        assertEquals(200, count("SELECT COUNT(*) FROM song_artist_map"))
        assertEquals(listOf("One", "Two"), database.transferArtistNames(listOf(tracks.last().stableId)).map { it.name })
    }

    @Test fun cancellationDuringFinalPlaylistWriteRollsBackSongsArtistsAndMembership() = runBlocking {
        lateinit var importJob: Job
        val mapWrites = AtomicInteger()
        recreateDatabaseWithQueryCallback { sql ->
            if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("playlist_song_map", ignoreCase = true)) {
                if (mapWrites.incrementAndGet() == 2) importJob.cancel()
            }
        }
        val second = remote.copy(stableId = "ZyXwVu98765")
        val document = TransferDocument(emptyList(), listOf(TransferPlaylist("cancel", "Cancel", listOf(remote, second))))
        val preview = repository.prepareImport(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document))
        importJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { repository.commitImport(preview) }
        importJob.start()
        importJob.join()
        assertEquals("Cancellation must occur while executing real membership writes", 2, mapWrites.get())
        assertTrue(importJob.isCancelled)
        assertEquals(0, count("SELECT COUNT(*) FROM song"))
        assertEquals(0, count("SELECT COUNT(*) FROM artist"))
        assertEquals(0, count("SELECT COUNT(*) FROM song_artist_map"))
        assertEquals(0, count("SELECT COUNT(*) FROM playlist"))
        assertEquals(0, count("SELECT COUNT(*) FROM playlist_song_map"))
    }

    private fun recreateDatabaseWithQueryCallback(onQuery: (String) -> Unit) {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) = onQuery(sqlQuery)
            }, Executor { it.run() })
            .build())
        repository = TransferRepository(database, LocalReferenceAccess { it == "/music/local.mp3" })
    }

    private fun count(sql: String): Int = database.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
}
