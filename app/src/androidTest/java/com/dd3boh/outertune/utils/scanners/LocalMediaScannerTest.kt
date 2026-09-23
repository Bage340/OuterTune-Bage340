package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** Exercises real file extraction and Room synchronization without opening an Activity or SAF UI. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class LocalMediaScannerTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: MusicDatabase
    private lateinit var scanner: LocalMediaScanner

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        directory = File(external, "scanner-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        database = MusicDatabase(
            Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        )
        LocalMediaScanner.destroyScanner(SCANNER_OWNER)
        scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            LocalMediaScanner.destroyScanner(SCANNER_OWNER)
        } finally {
            try {
                if (::database.isInitialized) database.close()
            } finally {
                if (::directory.isInitialized) check(directory.deleteRecursively())
            }
        }
    }

    @Test
    fun quickScanRegistersTagsAndFormat() = runBlocking {
        val file = copyAudio("a-before.flac", "a.flac")

        quick(file)

        val song = database.allLocalDbSongs().single()
        assertTags(song, "Scanner Song A", "Original Artist", "Original Album")
        assertEquals(file.absolutePath, song.song.localPath)
        assertNotNull(song.song.inLibrary)
        assertTrue(song.song.isLocal)
        val format = requireNotNull(database.format(song.id).first())
        assertEquals(song.id, format.id)
        assertEquals(44100, format.sampleRate)
        assertTrue(format.bitrate > 0)
        assertTrue(format.mimeType.isNotBlank())
        assertTrue(format.codecs.isNotBlank())
    }

    @Test
    fun quickScanKeepsExistingMetadata() = runBlocking {
        val file = copyAudio("a-before.flac", "a.flac")
        quick(file)
        val before = database.allLocalDbSongs().single()
        val formatBefore = database.format(before.id).first()

        copyAudio("a-after.flac", "a.flac")
        quick(file)

        val after = database.allLocalDbSongs().single()
        assertEquals(before.id, after.id)
        assertTags(after, "Scanner Song A", "Original Artist", "Original Album")
        assertEquals(formatBefore, database.format(after.id).first())
    }

    @Test
    fun fullScanRefreshesMatchedSongWithoutChangingId() = runBlocking {
        val file = copyAudio("a-before.flac", "a.flac")
        quick(file)
        val before = database.allLocalDbSongs().single()

        copyAudio("a-after.flac", "a.flac")
        full(file)

        val after = database.allLocalDbSongs().single()
        assertEquals(before.id, after.id)
        assertEquals(before.song.inLibrary, after.song.inLibrary)
        assertTags(after, "Scanner Song A", "Updated Artist", "Updated Album")
        assertNotNull(database.format(after.id).first())
    }

    @Test
    fun fullScanDisablesMissingSongWhenAnotherValidSongRemains() = runBlocking {
        val a = copyAudio("a-before.flac", "a.flac")
        val b = copyAudio("b.flac", "b.flac")
        quick(a, b)
        val before = database.allLocalDbSongs()
        val missingId = before.single { it.song.localPath == a.absolutePath }.id
        val remainingId = before.single { it.song.localPath == b.absolutePath }.id
        check(a.delete())
        assertTrue(!a.exists())

        full(b)

        val after = database.allLocalDbSongs()
        assertEquals(2, after.size)
        assertNull(after.single { it.id == missingId }.song.inLibrary)
        val remaining = after.single { it.id == remainingId }.song
        assertNotNull(remaining.inLibrary)
        assertEquals(remainingId, database.allLocalSongs().single().id)
    }

    @Test
    fun quickScanWithUnresolvedUriPreservesExistingSongs() = runBlocking {
        val a = copyAudio("a-before.flac", "a.flac")
        val b = copyAudio("b.flac", "b.flac")
        quick(a, b)
        val before = database.allLocalDbSongs().map { it.song }.toSet()

        assertThrows(ScannerAbortException::class.java) {
            runBlocking {
                scanner.quickSync(
                    database,
                    listOf(uri(b), Uri.parse("content://invalid.authority/unresolved")),
                    ScannerMatchCriteria.LEVEL_2,
                    strictFileNames = false,
                    strictFilePaths = false,
                )
            }
        }

        assertEquals(before, database.allLocalDbSongs().map { it.song }.toSet())
    }

    @Test
    fun fullScanWithFailedAudioExtractionPreservesExistingSongs() = runBlocking {
        val a = copyAudio("a-before.flac", "a.flac")
        val b = copyAudio("b.flac", "b.flac")
        quick(a, b)
        val before = database.allLocalDbSongs().map { it.song }.toSet()
        val missing = File(directory, "missing.flac")

        assertThrows(ScannerAbortException::class.java) {
            runBlocking {
                full(b, missing)
            }
        }

        assertEquals(before, database.allLocalDbSongs().map { it.song }.toSet())
    }

    @Test
    fun authoritativeScanDoesNotModifyRemoteDownloadedSongOrPlaylistRelation() = runBlocking {
        val missing = copyAudio("a-before.flac", "a.flac")
        val remaining = copyAudio("b.flac", "b.flac")
        quick(missing, remaining)
        val missingId = database.allLocalSongs().single { it.song.localPath == missing.absolutePath }.id
        val downloadedAt = LocalDateTime.of(2026, 1, 2, 3, 4)
        val remote = SongEntity(
            id = "remote-song",
            title = "Remote Song",
            inLibrary = downloadedAt,
            isLocal = false,
            localPath = File(directory, "downloaded.m4a").absolutePath,
            dateDownload = downloadedAt,
        )
        val playlist = PlaylistEntity(id = "remote-playlist", name = "Remote Playlist")
        val relation = PlaylistSongMap(playlistId = playlist.id, songId = remote.id)
        database.insert(remote)
        database.insert(playlist)
        database.insert(relation)

        full(remaining)

        assertNull(database.allLocalDbSongs().single { it.id == missingId }.song.inLibrary)
        assertEquals(remote, requireNotNull(database.song(remote.id).first()).song)
        assertEquals(
            relation.copy(id = database.songMapsToPlaylist(remote.id).single().id),
            database.songMapsToPlaylist(remote.id).single(),
        )
    }

    @Test
    fun failedEmptyScanPreservesRowsAndRelations() = runBlocking {
        val file = copyAudio("a-before.flac", "a.flac")
        quick(file)
        val localBefore = database.allLocalDbSongs().single().song
        val remote = SongEntity(
            id = "ytm-song",
            title = "YTM Song",
            inLibrary = LocalDateTime.of(2026, 2, 3, 4, 5),
            isLocal = false,
            localPath = null,
        )
        val playlist = PlaylistEntity(id = "ytm-playlist", name = "YTM Playlist")
        val relation = PlaylistSongMap(playlistId = playlist.id, songId = remote.id)
        database.insert(remote)
        database.insert(playlist)
        database.insert(relation)
        assertThrows(ScannerAbortException::class.java) {
            runBlocking { quick() }
        }

        assertEquals(localBefore, database.allLocalDbSongs().single().song)
        assertEquals(remote, requireNotNull(database.song(remote.id).first()).song)
        assertEquals(playlist.id, database.songMapsToPlaylist(remote.id).single().playlistId)
    }

    private fun copyAudio(asset: String, name: String): File = File(directory, name).also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("scanner/$asset").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        check(file.canRead() && file.length() > 0)
    }

    private fun uri(file: File): Uri {
        val storage = context.getSystemService(StorageManager::class.java)
        val root = requireNotNull(storage.primaryStorageVolume.directory)
        val documentId = "primary:${file.relativeTo(root).invariantSeparatorsPath}"
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:")
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId).also {
            assertEquals(file.canonicalFile, fileFromUri(context, it)?.canonicalFile)
        }
    }

    private suspend fun quick(vararg files: File) = scanner.quickSync(
        database,
        files.map(::uri),
        ScannerMatchCriteria.LEVEL_2,
        strictFileNames = false,
        strictFilePaths = false,
    )

    private suspend fun full(vararg files: File) = scanner.fullSync(
        database,
        files.map(::uri),
        ScannerMatchCriteria.LEVEL_2,
        strictFileNames = false,
        strictFilePaths = false,
    )

    private fun assertTags(song: Song, title: String, artist: String, album: String) {
        assertEquals(title, song.song.title)
        assertEquals(listOf(artist), song.artists.map { it.name })
        assertEquals(album, song.song.albumName)
        assertEquals(album, song.album?.title)
        assertNotNull(song.song.albumId)
        assertEquals(song.song.albumId, song.album?.id)
    }

    private companion object {
        const val SCANNER_OWNER = 41_002
    }
}
