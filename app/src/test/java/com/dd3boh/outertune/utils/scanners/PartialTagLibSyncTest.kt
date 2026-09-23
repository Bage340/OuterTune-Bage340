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
import com.dd3boh.outertune.db.entities.Song
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
