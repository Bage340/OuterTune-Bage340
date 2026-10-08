package com.dd3boh.outertune.utils.scanners

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.SongTempData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PartialTagLibSyncTest {
    private lateinit var context: Application
    private lateinit var storageRoot: File
    private lateinit var database: MusicDatabase
    private lateinit var scanner: LocalMediaScanner
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageRoot = requireNotNull(context.getExternalFilesDir(null))
        Shadows.shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("primary", storageRoot, "Primary", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .build()
        )
        directory = File(storageRoot, "partial-taglib-sync")
        check(directory.mkdirs())
        database = MusicDatabase(
            Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        )
        scanner = LocalMediaScanner(context, ScannerImpl.MEDIASTORE)
        LocalMediaScanner.scannerState.value = 0
        LocalMediaScanner.scannerRequestCancel = false
    }

    @After
    fun tearDown() {
        LocalMediaScanner.scannerState.value = -1
        LocalMediaScanner.scannerRequestCancel = false
        database.close()
        check(directory.deleteRecursively())
    }

    @Test
    fun unresolvedQuickSyncUriCannotDisableAnExistingSong() {
        val a = localFile("a.flac")
        val b = localFile("b.flac")
        seed(a, b)
        val before = database.allLocalDbSongs().map { it.song }.toSet()

        assertThrows(ScannerAbortException::class.java) {
            runBlocking {
                scanner.quickSync(
                    database,
                    listOf(uri(b), Uri.parse("content://invalid.authority/unresolved")),
                    ScannerMatchCriteria.LEVEL_2,
                    strictFileNames = false,
                    strictFilePaths = true,
                )
            }
        }

        assertEquals(before, database.allLocalDbSongs().map { it.song }.toSet())
    }

    @Test
    fun failedFullSyncExtractionCannotReconcileSuccessfulSubset() {
        val a = localFile("a.flac")
        val b = localFile("b.flac")
        seed(a, b)
        val before = database.allLocalDbSongs().map { it.song }.toSet()
        val missing = File(directory, "missing.flac")
        val scannerField = LocalMediaScanner::class.java.getDeclaredField("advancedScannerImpl")
        scannerField.isAccessible = true
        scannerField.set(scanner, object : MetadataScanner {
            override suspend fun getAllMetadataFromFile(file: File): SongTempData = SongTempData(
                Song(
                    SongEntity(
                        id = "scanned-${file.name}",
                        title = file.name,
                        inLibrary = LocalDateTime.of(2026, 1, 1, 0, 0),
                        isLocal = true,
                        localPath = file.absolutePath,
                    ),
                    artists = emptyList(),
                ),
                format = null,
            )
        })

        assertThrows(ScannerAbortException::class.java) {
            runBlocking {
                scanner.fullSync(
                    database,
                    listOf(uri(b), uri(missing)),
                    ScannerMatchCriteria.LEVEL_2,
                    strictFileNames = false,
                    strictFilePaths = true,
                )
            }
        }

        assertEquals(before, database.allLocalDbSongs().map { it.song }.toSet())
    }

    private fun localFile(name: String): File = File(directory, name).also {
        check(it.createNewFile())
    }

    @Test
    fun unexpectedTagLibFailureCannotReplaceExistingMetadataWithFallback() = runBlocking {
        val a = localFile("tagged.flac")
        val b = localFile("readable.flac")
        seed(a, b)
        database.insert(ArtistEntity("LAretained", "Retained Artist", isLocal = true))
        database.insert(AlbumEntity("LBretained", title = "Retained Album", songCount = 1, duration = 123, isLocal = true))
        database.insert(SongArtistMap(a.name, "LAretained", 0))
        database.insert(SongAlbumMap(a.name, "LBretained", 0))
        val original = database.allLocalDbSongs().first { it.id == a.name }.song
        database.update(original.copy(title = "Retained Title", duration = 123, liked = true,
            albumId = "LBretained", albumName = "Retained Album"))
        val before = database.allLocalDbSongs().toSet()
        val scannerField = LocalMediaScanner::class.java.getDeclaredField("advancedScannerImpl")
        scannerField.isAccessible = true
        scannerField.set(scanner, object : MetadataScanner {
            override suspend fun getAllMetadataFromFile(file: File): SongTempData {
                if (file == a) throw RuntimeException("TagLib failed to read metadata")
                return SongTempData(Song(SongEntity("new-${file.name}", "Changed Title",
                    isLocal = true, inLibrary = LocalDateTime.now(), localPath = file.absolutePath),
                    artists = emptyList()), format = null)
            }
        })

        assertThrows(ScannerAbortException::class.java) {
            runBlocking {
                scanner.fullSync(database, listOf(uri(b), uri(a)), ScannerMatchCriteria.LEVEL_2,
                    strictFileNames = false, strictFilePaths = true)
            }
        }
        assertEquals(before, database.allLocalDbSongs().toSet())
    }

    @Test
    fun repeatedScansRetainDistinctExistingAlbumIdentitiesAndSavedState() = runBlocking {
        val a = localFile("album-a.flac")
        val b = localFile("album-b.flac")
        seed(a, b)
        val savedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val albums = listOf(
            AlbumEntity("LBone", title = "Same Title", songCount = 1, duration = 30,
                isLocal = true, bookmarkedAt = savedAt, lastUpdateTime = savedAt),
            AlbumEntity("LBtwo", title = "Same Title", songCount = 1, duration = 40,
                isLocal = true, bookmarkedAt = savedAt.plusDays(1), lastUpdateTime = savedAt),
        )
        albums.forEach(database::insert)
        for ((file, album) in listOf(a to albums[0], b to albums[1])) {
            val original = database.allLocalDbSongs().first { it.id == file.name }.song
            database.update(original.copy(albumId = album.id, albumName = album.title, duration = album.duration))
            database.insert(SongAlbumMap(file.name, album.id, 7))
        }
        val before = database.allLocalDbSongs().toSet()
        for (refreshExisting in listOf(false, true, true)) {
            LocalMediaScanner.scannerState.value = 0
            scanner.syncDB(database, ArrayList(before.map { SongTempData(it, format = null) }),
                ScannerMatchCriteria.LEVEL_2, strictFileNames = false, strictFilePaths = true,
                refreshExisting = refreshExisting, noDisable = true)

            assertEquals(before, database.allLocalDbSongs().toSet())
            assertEquals(albums.toSet(), database.allLocalAlbumsByName().toSet())
            assertEquals(listOf(7, 7), listOf(a, b).map { database.songAlbumMaps(it.name).single().index })
        }
    }

    @Test
    fun retaggingAndRemovingAlbumRecomputeBothAlbumTotalsWithoutLosingBookmarks() = runBlocking {
        val file = localFile("retagged.flac")
        seed(file)
        val savedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val oldAlbum = AlbumEntity("LBold", title = "Old Album", songCount = 1, duration = 30,
            isLocal = true, bookmarkedAt = savedAt, lastUpdateTime = savedAt)
        val newAlbum = AlbumEntity("LBnew", title = "New Album", songCount = 0, duration = 0,
            isLocal = true, bookmarkedAt = savedAt.plusDays(1), lastUpdateTime = savedAt)
        listOf(oldAlbum, newAlbum).forEach(database::insert)
        val original = database.allLocalDbSongs().single().song
        database.update(original.copy(albumId = oldAlbum.id, albumName = oldAlbum.title, duration = 30))
        database.insert(SongAlbumMap(file.name, oldAlbum.id, 3))

        val before = database.allLocalDbSongs().single()
        scanner.syncDB(database, arrayListOf(SongTempData(before.copy(
            song = before.song.copy(duration = 45, albumName = newAlbum.title), album = newAlbum), null)),
            ScannerMatchCriteria.LEVEL_2, false, true, refreshExisting = true, noDisable = true)
        assertEquals(oldAlbum.copy(songCount = 0, duration = 0), database.albumById(oldAlbum.id))
        assertEquals(newAlbum.copy(songCount = 1, duration = 45), database.albumById(newAlbum.id))
        assertEquals(newAlbum.id, database.allLocalDbSongs().single().song.albumId)
        assertEquals(listOf(SongAlbumMap(file.name, newAlbum.id, 0)), database.songAlbumMaps(file.name))

        LocalMediaScanner.scannerState.value = 0
        val retagged = database.allLocalDbSongs().single()
        scanner.syncDB(database, arrayListOf(SongTempData(retagged.copy(
            song = retagged.song.copy(albumId = null, albumName = null), album = null), null)),
            ScannerMatchCriteria.LEVEL_2, false, true, refreshExisting = true, noDisable = true)
        assertEquals(newAlbum, database.albumById(newAlbum.id))
        assertEquals(null, database.allLocalDbSongs().single().song.albumId)
        assertEquals(emptyList<SongAlbumMap>(), database.songAlbumMaps(file.name))
    }

    private fun seed(vararg files: File) {
        files.forEach { file ->
            database.insert(
                SongEntity(
                    id = file.name,
                    title = file.name,
                    inLibrary = LocalDateTime.of(2026, 1, 1, 0, 0),
                    isLocal = true,
                    localPath = file.absolutePath,
                )
            )
        }
    }

    private fun uri(file: File): Uri {
        val documentId = "primary:${file.relativeTo(storageRoot).invariantSeparatorsPath}"
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:")
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }
}
