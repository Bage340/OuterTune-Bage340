package androidx.documentfile.provider

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.playback.downloadManager.buildDownloadFileName
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DownloadDirectoryScanFailureTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedTraversalDoesNotReplacePreviouslyValidatedFileIndex() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = DownloadDirectoryManagerOt(context, Uri.EMPTY, emptyList())
        val audio = temporaryFolder.newFile(buildDownloadFileName("Song", "song", BuildConfig.FLAVOR.startsWith("preview"))).apply { writeText("audio") }
        val directory = ProviderDirectory(arrayOf(DocumentFile.fromFile(audio)))
        manager.allDirs = listOf(directory)
        val before = manager.getAvailableFiles(false)
        assertTrue("song" in before)
        directory.failQuery = true

        assertThrows(IOException::class.java) { manager.getAvailableFiles(false) }
        assertEquals(before, manager.getAvailableFiles(true))
        assertTrue(audio.isFile)
    }

    @Test fun unreadableConfiguredDirectoryIsNotAnEmptySuccessfulScan() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = DownloadDirectoryManagerOt(context, Uri.EMPTY, emptyList())
        manager.allDirs = listOf(ProviderDirectory(emptyArray()).apply { readable = false })

        assertThrows(IOException::class.java) { manager.getAvailableFiles(false) }
    }

    @Test fun inaccessibleRawTreeIsNotSilentlyDroppedAndInternalOnlyIsAllowed() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val internal = DownloadDirectoryManagerOt(context, Uri.EMPTY, emptyList())
        assertTrue(internal.getAvailableFiles(false).isEmpty())
        val unavailable = DownloadDirectoryManagerOt(
            context, Uri.parse("content://missing/tree/music"), emptyList(),
        )
        assertThrows(IOException::class.java) { unavailable.getAvailableFiles(false) }
    }

    @Test fun strictTreeMetadataFailurePreservesIndexButKnownZeroSizeDoesNotCountAsReady() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(app) {
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        }
        val provider = MetadataProvider()
        ShadowContentResolver.registerProviderInternal("download.fixture", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("download.fixture", "root")
        val manager = DownloadDirectoryManagerOt(context, tree, emptyList())
        val before = manager.getAvailableFiles(false)
        assertTrue("song" in before)

        provider.failSize = true
        assertThrows(IOException::class.java) { manager.getAvailableFiles(false) }
        assertEquals(before, manager.getAvailableFiles(true))

        provider.failSize = false
        provider.size = -1L
        assertThrows(IOException::class.java) { manager.getAvailableFiles(false) }
        assertEquals(before, manager.getAvailableFiles(true))
        provider.size = 0L
        assertTrue(manager.getAvailableFiles(false).isEmpty())
    }

    private class MetadataProvider : ContentProvider() {
        var failSize = false
        var size = 42L
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            val children = uri.pathSegments.last() == "children"
            val child = children || DocumentsContract.getDocumentId(uri) == "song"
            if (child && failSize && DocumentsContract.Document.COLUMN_SIZE in columns) {
                throw IOException("Provider cannot return audio size")
            }
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (child) "song" else "root"
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (child) {
                            buildDownloadFileName("Song", "song", BuildConfig.FLAVOR.startsWith("preview"))
                        } else "downloads"
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (child) "audio/mka" else DocumentsContract.Document.MIME_TYPE_DIR
                        DocumentsContract.Document.COLUMN_SIZE -> if (child) size else 0L
                        else -> null
                    }
                })
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class ProviderDirectory(private val children: Array<DocumentFile>) : DocumentFile(null) {
        var failQuery = false
        var readable = true
        override fun getUri() = Uri.parse("content://fixture/tree/downloads")
        override fun getName() = "downloads"
        override fun getType() = "vnd.android.document/directory"
        override fun isDirectory() = true
        override fun isFile() = false
        override fun isVirtual() = false
        override fun lastModified() = 1L
        override fun length() = 0L
        override fun canRead() = readable
        override fun canWrite() = false
        override fun exists() = true
        override fun delete() = false
        override fun renameTo(displayName: String) = false
        override fun listFiles(): Array<DocumentFile> {
            if (failQuery) throw IOException("Provider query failed")
            return children
        }
        override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
        override fun createDirectory(displayName: String): DocumentFile? = null
    }
}
