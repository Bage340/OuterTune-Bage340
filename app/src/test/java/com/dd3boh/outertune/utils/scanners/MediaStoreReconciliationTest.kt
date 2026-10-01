package com.dd3boh.outertune.utils.scanners

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class MediaStoreReconciliationTest {
    private lateinit var database: MusicDatabase
    private lateinit var scanner: LocalMediaScanner
    private lateinit var provider: ScanProvider
    private val rootName = "scanner-test-${UUID.randomUUID()}"
    private val rootPath get() = File(Environment.getExternalStorageDirectory(), rootName).absolutePath
    private val rootUri get() = DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$rootName"),
        "primary:$rootName",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries().build())
        scanner = LocalMediaScanner(context, ScannerImpl.MEDIASTORE)
        provider = ScanProvider("$rootPath/found.mp3")
        check(File(rootPath).mkdirs())
        ShadowContentResolver.registerProviderInternal("media", provider)
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        LocalMediaScanner.scannerRequestCancel = false
        LocalMediaScanner.scannerState.value = 0
    }

    @After
    fun tearDown() {
        database.close()
        LocalMediaScanner.scannerRequestCancel = false
        LocalMediaScanner.scannerState.value = -1
        check(File(rootPath).deleteRecursively())
    }

    @Test
    fun selectedRootOnlyDisablesConfirmedMissingSongsWithinItsCoverage() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 1, 0, 0)
        database.insert(SongEntity("missing", "Missing", localPath = "$rootPath/missing.mp3", isLocal = true, inLibrary = date))
        database.insert(SongEntity("outside", "Outside", localPath = "$rootPath-Other/outside.mp3", isLocal = true, inLibrary = date))
        database.insert(SongEntity("remote", "Remote", localPath = "$rootPath/missing.mp3", inLibrary = date))

        scan()

        assertNull(database.song("missing").first()!!.song.inLibrary)
        assertEquals(date, database.song("outside").first()!!.song.inLibrary)
        assertEquals(date, database.song("remote").first()!!.song.inLibrary)
    }

    @Test
    fun revokedRootDuringQueryAbortsBeforeAnyReconciliation() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 1, 0, 0)
        database.insert(SongEntity("existing", "Existing", localPath = "$rootPath/existing.mp3", isLocal = true, inLibrary = date))
        provider.revokeDuringQuery = true

        val failure = runCatching { scan() }.exceptionOrNull()

        assertTrue(failure is ScannerAbortException)
        assertEquals(listOf("existing"), database.allLocalDbSongs().map { it.song.id })
        assertEquals(date, database.song("existing").first()!!.song.inLibrary)
    }

    @Test
    fun fileMissingFromMediaStoreIndexRemainsInLibraryWhenItStillExists() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 1, 0, 0)
        val unindexed = File(rootPath, "unindexed.mp3").apply { writeText("audio-fixture") }
        database.insert(SongEntity("unindexed", "Unindexed", localPath = unindexed.absolutePath, isLocal = true, inLibrary = date))

        scan()

        assertEquals(date, database.song("unindexed").first()!!.song.inLibrary)
    }

    @Test
    fun globalMediaStoreDiscoveryCannotHideAnUnverifiedMissingVolume() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 1, 0, 0)
        database.insert(SongEntity("unavailable-volume", "On removable storage", localPath = "/storage/unavailable-volume/Music/saved.mp3", isLocal = true, inLibrary = date))

        scanner.fullMediaStoreSync(database, emptyList(), emptyList(), ScannerMatchCriteria.LEVEL_1,
            strictFileNames = true, strictFilePaths = true, refreshExisting = false)

        assertEquals(date, database.song("unavailable-volume").first()!!.song.inLibrary)
        assertEquals(2, database.allLocalDbSongs().size)
    }

    @Test
    fun globalMediaStoreDiscoveryPreservesContentUriRowsAndReenablesFoundSongs() = runBlocking {
        val date = LocalDateTime.of(2025, 1, 1, 0, 0)
        database.insert(SongEntity("content-uri", "Saved URI", localPath = "content://local/audio/saved", isLocal = true, inLibrary = date))
        database.insert(SongEntity("found-again", "Previously hidden", localPath = "$rootPath/found.mp3", isLocal = true))

        scanner.fullMediaStoreSync(database, emptyList(), emptyList(), ScannerMatchCriteria.LEVEL_1,
            strictFileNames = true, strictFilePaths = true, refreshExisting = false)

        assertEquals(date, database.song("content-uri").first()!!.song.inLibrary)
        assertNotNull(database.song("found-again").first()!!.song.inLibrary)
        assertEquals(2, database.allLocalDbSongs().size)
    }

    @Test
    fun mediaStoreFileSizeRemainsBytesRatherThanDuration() = runBlocking {
        scan()

        val song = database.allLocalDbSongs().single().song
        assertEquals(180, song.duration)
        assertEquals(12345678L, database.format(song.id).first()!!.contentLength)
        assertNull(database.format(song.id).first()!!.sampleRate)
    }

    private suspend fun scan() = scanner.fullMediaStoreSync(
        database, listOf(rootUri), emptyList(), ScannerMatchCriteria.LEVEL_1,
        strictFileNames = true, strictFilePaths = true, refreshExisting = false,
    )

    private class ScanProvider(private val path: String) : ContentProvider() {
        var revokeDuringQuery = false
        private var available = true
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                if (uri.authority == "media") {
                    if (revokeDuringQuery) available = false
                    addRow(columns.map { column ->
                        when (column) {
                            MediaStore.Audio.Media._ID -> 1
                            MediaStore.Audio.Media.DISPLAY_NAME -> "found.mp3"
                            MediaStore.Audio.Media.TITLE -> "Found"
                            MediaStore.Audio.Media.ARTIST -> "Artist"
                            MediaStore.Audio.Media.DURATION -> 180000
                            MediaStore.Audio.Media.DATA -> path
                            MediaStore.Audio.Media.MIME_TYPE -> "audio/mpeg"
                            MediaStore.Audio.Media.SIZE -> 12345678L
                            else -> null
                        }
                    })
                } else if (available) {
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "primary:Music"
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "Music"
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            else -> null
                        }
                    })
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
