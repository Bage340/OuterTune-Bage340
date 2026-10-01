package com.dd3boh.outertune.utils.scanners

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.SongTempData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class ScanDatabaseAtomicityTest {
    private lateinit var database: MusicDatabase
    private lateinit var scanner: LocalMediaScanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val delegate = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = MusicDatabase(delegate)
        scanner = LocalMediaScanner(context, ScannerImpl.MEDIASTORE)
        LocalMediaScanner.scannerState.value = 0
        LocalMediaScanner.scannerRequestCancel = false
    }

    @After
    fun tearDown() {
        LocalMediaScanner.scannerState.value = -1
        LocalMediaScanner.scannerRequestCancel = false
        database.close()
    }

    @Test
    fun failedScannerCommitRollsBackAllProspectiveMutations() {
        database.insert(localSong("existing", "/music/existing.mp3").song.song)
        database.openHelper.writableDatabase.execSQL(
            """
                CREATE TRIGGER fail_second_scanned_song
                BEFORE INSERT ON song
                WHEN NEW.id = 'scan-2'
                BEGIN
                    SELECT RAISE(ABORT, 'forced scanner failure');
                END
            """.trimIndent()
        )

        val failure = runCatching {
            runBlocking {
                scanner.syncDB(
                    database = database,
                    newSongs = arrayListOf(
                        localSong("scan-1", "/music/scan-1.mp3"),
                        localSong("scan-2", "/music/scan-2.mp3"),
                    ),
                    matchStrength = ScannerMatchCriteria.LEVEL_1,
                    strictFileNames = true,
                    strictFilePaths = true,
                    noDisable = true,
                )
            }
        }.exceptionOrNull()

        if (failure == null) {
            val queuedTransactionsDrained = CountDownLatch(1)
            database.transaction { queuedTransactionsDrained.countDown() }
            assertTrue(queuedTransactionsDrained.await(5, TimeUnit.SECONDS))
        }

        assertEquals(listOf("existing"), database.allLocalDbSongs().map { it.song.id })
        assertNotNull(failure)
    }

    @Test
    fun scannerAbortRollsBackAllProspectiveMutations() {
        database.insert(localSong("existing", "/music/existing.mp3").song.song)

        val failure = runCatching {
            runBlocking {
                database.withScannerTransaction {
                    insert(localSong("scan-1", "/music/scan-1.mp3").song.song)
                    throw ScannerAbortException("forced scanner cancellation")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is ScannerAbortException)
        assertEquals(listOf("existing"), database.allLocalDbSongs().map { it.song.id })
    }

    @Test
    fun localDuplicateCleanupPreservesRemoteSongWithTheSamePath() = runBlocking {
        val path = "/music/shared.mp3"
        val remote = SongEntity(
            id = "remote",
            title = "Remote download",
            isLocal = false,
            localPath = path,
        )
        val local = localSong("local", path)
        val playlist = PlaylistEntity(id = "playlist", name = "Saved songs")
        database.insert(remote)
        database.insert(local.song.song)
        database.insert(playlist)
        database.insert(PlaylistSongMap(playlistId = playlist.id, songId = remote.id))

        scanner.syncDB(
            database = database,
            newSongs = arrayListOf(local),
            matchStrength = ScannerMatchCriteria.LEVEL_1,
            strictFileNames = true,
            strictFilePaths = true,
        )

        assertNotNull(database.song(remote.id).first())
        assertNotNull(database.song(local.song.id).first())
        assertEquals(playlist.id, database.songMapsToPlaylist(remote.id).single().playlistId)
    }

    @Test
    fun refreshingMetadataPreservesLikesLibraryDateDownloadAndPlaylistIdentity() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 2, 3, 4)
        val original = localSong("existing", "/music/existing.mp3").song.song.copy(
            liked = true, likedDate = date, inLibrary = date, dateDownload = date,
        )
        val playlist = PlaylistEntity(id = "playlist", name = "Saved songs")
        database.insert(original)
        database.insert(playlist)
        database.insert(PlaylistSongMap(playlistId = playlist.id, songId = original.id))

        scanner.syncDB(
            database, arrayListOf(localSong("temporary-scan-id", original.localPath!!)),
            ScannerMatchCriteria.LEVEL_1, true, true, refreshExisting = true, noDisable = true,
        )

        val updated = database.song(original.id).first()!!.song
        assertTrue(updated.liked)
        assertEquals(date, updated.likedDate)
        assertEquals(date, updated.inLibrary)
        assertEquals(date, updated.dateDownload)
        assertEquals(playlist.id, database.songMapsToPlaylist(original.id).single().playlistId)
        assertEquals("temporary-scan-id", updated.title)
    }

    @Test
    fun reenablingKnownSongPreservesItsLikesAndDownloadDate() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 2, 3, 4)
        val original = localSong("existing", "/music/existing.mp3").song.song.copy(
            liked = true, likedDate = date, inLibrary = null, dateDownload = date,
        )
        database.insert(original)

        scanner.syncDB(
            database, arrayListOf(localSong("temporary-scan-id", original.localPath!!)),
            ScannerMatchCriteria.LEVEL_1, true, true, noDisable = true,
        )

        val updated = database.song(original.id).first()!!.song
        assertTrue(updated.liked)
        assertEquals(date, updated.likedDate)
        assertEquals(date, updated.dateDownload)
        assertNotNull(updated.inLibrary)
        assertEquals(original.title, updated.title)
    }

    @Test
    fun scannerCleanupPreservesDuplicateLocalIdsAndTheirPlaylistLinks() = runBlocking {
        val path = "/music/shared.mp3"
        val first = localSong("first", path)
        val second = localSong("second", path)
        val playlist = PlaylistEntity(id = "playlist", name = "Saved songs")
        database.insert(first.song.song)
        database.insert(second.song.song.copy(liked = true))
        database.insert(playlist)
        database.insert(PlaylistSongMap(playlistId = playlist.id, songId = second.song.id))

        scanner.syncDB(database, arrayListOf(first), ScannerMatchCriteria.LEVEL_1, true, true)

        assertNotNull(database.song(first.song.id).first())
        assertTrue(database.song(second.song.id).first()!!.song.liked)
        assertEquals(playlist.id, database.songMapsToPlaylist(second.song.id).single().playlistId)
    }

    @Test
    fun concurrentScannerTransactionsHideRolledBackRowsAndSerializeNextCommit() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseRollback = CompletableDeferred<Unit>()
        val secondRequested = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            runCatching {
                database.withScannerTransaction {
                    insert(localSong("rolled-back", "/music/rolled-back.mp3").song.song)
                    firstEntered.complete(Unit)
                    releaseRollback.await()
                    throw ScannerAbortException("forced concurrent rollback")
                }
            }.exceptionOrNull()
        }
        firstEntered.await()

        val second = async(Dispatchers.Default) {
            secondRequested.complete(Unit)
            database.withScannerTransaction {
                secondEntered.complete(Unit)
                insert(localSong("committed", "/music/committed.mp3").song.song)
            }
        }
        secondRequested.await()
        assertFalse(secondEntered.isCompleted)

        releaseRollback.complete(Unit)
        assertTrue(first.await() is ScannerAbortException)
        second.await()

        assertEquals(listOf("committed"), database.allLocalDbSongs().map { it.song.id })
    }

    private fun localSong(id: String, path: String): SongTempData {
        return SongTempData(
            song = Song(
                song = SongEntity(
                    id = id,
                    title = id,
                    inLibrary = LocalDateTime.of(2026, 1, 1, 0, 0),
                    isLocal = true,
                    localPath = path,
                ),
                artists = emptyList(),
            ),
            format = null,
        )
    }
}
