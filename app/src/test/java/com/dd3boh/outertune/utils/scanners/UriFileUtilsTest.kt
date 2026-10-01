package com.dd3boh.outertune.utils.scanners

import android.app.Application
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class UriFileUtilsTest {
    @Test
    fun android10SelectedTreeCanResolveWithoutADocumentSegment() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")

        assertEquals(File(Environment.getExternalStorageDirectory(), "Music").absolutePath,
            absoluteFilePathFromUri(context, tree))
    }

    @Test
    fun android10DocumentPathPreservesColonsInFileNames() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album:Live/song.flac")

        assertEquals(File(Environment.getExternalStorageDirectory(), "Music/Album:Live/song.flac").absolutePath,
            absoluteFilePathFromUri(context, uri))
    }

    @Test
    fun documentFromUnknownAuthorityCannotResolveToExternalStorage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val uri = DocumentsContract.buildTreeDocumentUri("example.documents", "primary:Music")

        assertNull(fileFromUri(context, uri))
    }

    @Test
    fun documentTraversalCannotEscapeItsStorageVolume() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:../outside/song.flac")

        assertNull(fileFromUri(context, uri))
    }
}
