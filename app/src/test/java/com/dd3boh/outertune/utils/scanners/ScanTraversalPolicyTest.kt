package com.dd3boh.outertune.utils.scanners

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.TreeDocumentFileOt
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.ui.screens.settings.fragments.handleManualScanException
import com.dd3boh.outertune.ui.screens.settings.fragments.shouldRecordSuccessfulManualScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScanTraversalPolicyTest {
    @Test
    fun authoritativeListingPropagatesChildQueryFailure() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ShadowContentResolver.registerProviderInternal(
            AUTHORITY,
            FailingChildDocumentsProvider(),
        )
        val directory = TreeDocumentFileOt(null, context, ROOT_URI)

        assertThrows(IllegalStateException::class.java) {
            LocalMediaScanner.scanDfRecursive(directory, arrayListOf(), failFast = true)
        }
    }

    @Test
    fun ordinaryListingRetainsEmptyResultFallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ShadowContentResolver.registerProviderInternal(
            AUTHORITY,
            FailingChildDocumentsProvider(),
        )
        val directory = TreeDocumentFileOt(null, context, ROOT_URI)
        val child = directory.listFiles().single()

        assertTrue(child.listFiles().isEmpty())
    }

    @Test
    fun cachedChildMimeKeepsAuthoritativeTraversalCompleteWhenMetadataLookupFails() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ShadowContentResolver.registerProviderInternal(
            AUTHORITY,
            FailingMetadataDocumentsProvider(),
        )
        val directory = TreeDocumentFileOt(null, context, ROOT_URI)
        val result = arrayListOf<androidx.documentfile.provider.DocumentFile>()

        LocalMediaScanner.scanDfRecursive(directory, result, failFast = true) {
            it.type?.startsWith("audio") == true
        }

        assertEquals(setOf("inside.mp3", "outside.mp3"), result.mapNotNull { it.name }.toSet())
    }

    @Test
    fun manualScanFailurePolicyPreservesCancellationAndReportsUnexpectedExceptions() {
        val reported = mutableListOf<Exception>()
        val reporter: (Exception) -> Unit = { reported.add(it) }
        val cancellation = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            handleManualScanException(cancellation, reporter)
        }
        assertSame(cancellation, thrown)
        assertTrue(reported.isEmpty())

        val unexpected = IllegalStateException("unexpected")
        assertTrue(handleManualScanException(unexpected, reporter))
        assertSame(unexpected, reported.single())
    }

    @Test
    fun failedManualScanDoesNotAdvanceSuccessfulScanTimestamp() {
        assertTrue(shouldRecordSuccessfulManualScan(scannerFailure = false))
        assertFalse(shouldRecordSuccessfulManualScan(scannerFailure = true))
    }

    private class FailingMetadataDocumentsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = requireNotNull(projection)
            val documentId = DocumentsContract.getDocumentId(uri)
            if (uri.pathSegments.lastOrNull() == "children") {
                return when (documentId) {
                    ROOT_DOCUMENT_ID -> cursor(
                        columns,
                        listOf(
                            Triple(CHILD_DOCUMENT_ID, "Music", DocumentsContract.Document.MIME_TYPE_DIR),
                            Triple(OUTSIDE_DOCUMENT_ID, "outside.mp3", "audio/mpeg"),
                        ),
                    )

                    CHILD_DOCUMENT_ID -> cursor(
                        columns,
                        listOf(Triple(INSIDE_DOCUMENT_ID, "inside.mp3", "audio/mpeg")),
                    )

                    else -> cursor(columns, emptyList())
                }
            }
            if (documentId == CHILD_DOCUMENT_ID) {
                throw IllegalStateException("directory metadata unavailable")
            }
            val mime = if (documentId == ROOT_DOCUMENT_ID) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                "audio/mpeg"
            }
            return cursor(columns, listOf(Triple(documentId, "$documentId.mp3", mime)))
        }

        private fun cursor(
            columns: Array<out String>,
            rows: List<Triple<String, String, String>>,
        ): Cursor = MatrixCursor(columns).apply {
            rows.forEach { (documentId, displayName, mimeType) ->
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
                        else -> null
                    }
                })
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private class FailingChildDocumentsProvider : ContentProvider() {
        private var childQueryCount = 0

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = requireNotNull(projection)
            if (uri.pathSegments.lastOrNull() == "children") {
                if (DocumentsContract.getDocumentId(uri) != ROOT_DOCUMENT_ID) {
                    if (childQueryCount++ == 0) {
                        throw IllegalStateException("child listing unavailable")
                    }
                    return MatrixCursor(columns)
                }
                return cursor(
                    columns,
                    CHILD_DOCUMENT_ID,
                    "Music",
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            return cursor(
                columns,
                CHILD_DOCUMENT_ID,
                "Music",
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
        }

        private fun cursor(
            columns: Array<out String>,
            documentId: String,
            displayName: String,
            mimeType: String,
        ): Cursor = MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
                    else -> null
                }
            })
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val AUTHORITY = "com.dd3boh.outertune.test.documents"
        const val ROOT_DOCUMENT_ID = "root"
        const val CHILD_DOCUMENT_ID = "music"
        const val OUTSIDE_DOCUMENT_ID = "outside"
        const val INSIDE_DOCUMENT_ID = "inside"
        val ROOT_URI: Uri = Uri.parse(
            "content://$AUTHORITY/tree/$ROOT_DOCUMENT_ID/document/$ROOT_DOCUMENT_ID"
        )
    }
}
