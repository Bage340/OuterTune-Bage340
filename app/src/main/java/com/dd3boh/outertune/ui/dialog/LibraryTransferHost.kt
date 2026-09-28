package com.dd3boh.outertune.ui.dialog

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.transfer.AndroidLocalReferenceAccess
import com.dd3boh.outertune.transfer.TransferCodec
import com.dd3boh.outertune.transfer.TransferDisposition
import com.dd3boh.outertune.transfer.TransferDocument
import com.dd3boh.outertune.transfer.TransferException
import com.dd3boh.outertune.transfer.TransferFormat
import com.dd3boh.outertune.transfer.TransferImportPreview
import com.dd3boh.outertune.transfer.TransferLimits
import com.dd3boh.outertune.transfer.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

sealed interface LibraryTransferRequest {
    data class ExportPlaylist(val id: String, val name: String) : LibraryTransferRequest
    data class ExportDocument(val document: TransferDocument, val name: String) : LibraryTransferRequest
    data object ExportLibrary : LibraryTransferRequest
    data object Import : LibraryTransferRequest
}

private data class ExportTarget(
    val playlistIds: List<String>?,
    val includeLibrary: Boolean,
    val fileName: String,
    val document: TransferDocument? = null,
)

/** The host stays in composition while the system document picker is open. */
@Composable
fun LibraryTransferHost(request: LibraryTransferRequest?, onRequestConsumed: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val repository = remember(database, context) {
        TransferRepository(database, AndroidLocalReferenceAccess(context))
    }
    val scope = rememberCoroutineScope()
    var exportTarget by remember { mutableStateOf<ExportTarget?>(null) }
    var preview by remember { mutableStateOf<TransferImportPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun report(errorValue: Throwable) {
        error = when (errorValue) {
            is TransferException, is IOException -> errorValue.message
            else -> null
        } ?: resources.getString(R.string.transfer_failed)
    }

    fun writeExport(uri: Uri?, format: TransferFormat) {
        if (uri == null) return
        val target = exportTarget ?: return
        busy = true
        scope.launch {
            try {
                val bytes = target.document?.let { document ->
                    withContext(Dispatchers.IO) { TransferCodec.encode(format, document) }
                } ?: repository.export(format, target.playlistIds, target.includeLibrary)
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Unable to open the selected document")
                    output.use { it.write(bytes) }
                }
                exportTarget = null
                Toast.makeText(context, R.string.transfer_exported, Toast.LENGTH_LONG).show()
            } catch (failure: Exception) {
                report(failure)
            } finally {
                busy = false
            }
        }
    }

    val jsonSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeExport(it, TransferFormat.JSON)
    }
    val csvSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        writeExport(it, TransferFormat.CSV)
    }
    val m3uSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) {
        writeExport(it, TransferFormat.M3U8)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val (bytes, name, mime) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    Triple(readBoundedDocument(resolver.openInputStream(uri)), displayName, resolver.getType(uri))
                }
                val format = detectTransferFormat(name, mime, bytes)
                preview = repository.prepareImport(format, bytes)
            } catch (failure: Exception) {
                report(failure)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(request) {
        when (request) {
            is LibraryTransferRequest.ExportPlaylist -> {
                exportTarget = ExportTarget(listOf(request.id), false, request.name.safeTransferFileName())
                onRequestConsumed()
            }
            is LibraryTransferRequest.ExportDocument -> {
                exportTarget = ExportTarget(null, false, request.name.safeTransferFileName(), request.document)
                onRequestConsumed()
            }
            LibraryTransferRequest.ExportLibrary -> {
                exportTarget = ExportTarget(null, true, "outertune-library")
                onRequestConsumed()
            }
            LibraryTransferRequest.Import -> {
                onRequestConsumed()
                open.launch(arrayOf("*/*"))
            }
            null -> Unit
        }
    }

    val target = exportTarget
    if (target != null && !busy && preview == null) {
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = { Text(stringResource(R.string.transfer_choose_format)) },
            text = {
                Column {
                    TextButton(onClick = { jsonSave.launch("${target.fileName}.json") }) { Text("JSON") }
                    TextButton(onClick = { csvSave.launch("${target.fileName}.csv") }) { Text("CSV") }
                    if (target.playlistIds?.size == 1 || target.document?.playlists?.size == 1) {
                        TextButton(onClick = { m3uSave.launch("${target.fileName}.m3u8") }) { Text("M3U8") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportTarget = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.transfer_in_progress)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
    preview?.let { stagedPreview ->
        val tracks = stagedPreview.staged.library + stagedPreview.staged.playlists.flatMap { it.tracks }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.transfer_import_preview_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.transfer_import_preview_counts,
                        stagedPreview.document.playlists.size,
                        tracks.size,
                        tracks.count { it.disposition == TransferDisposition.CREATE },
                        tracks.count { it.disposition == TransferDisposition.EXISTING },
                        stagedPreview.staged.unresolvedCount,
                        stagedPreview.staged.duplicateCount,
                        0,
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    busy = true
                    scope.launch {
                        try {
                            val result = repository.commitImport(stagedPreview)
                            Toast.makeText(
                                context,
                                resources.getString(R.string.transfer_imported, result.createdSongs, result.addedPlaylistEntries),
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (failure: Exception) {
                            report(failure)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.transfer_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.transfer_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

private fun String.safeTransferFileName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "playlist" }

internal fun readBoundedDocument(input: java.io.InputStream?): ByteArray {
    if (input == null) throw IOException("Unable to open the selected document")
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var zeroReads = 0
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, TransferLimits.MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                if (++zeroReads >= 3) throw IOException("Selected document did not provide data")
                continue
            }
            zeroReads = 0
            output.write(buffer, 0, count)
            if (output.size() > TransferLimits.MAX_BYTES) throw TransferException("Import exceeds size limit")
        }
        return output.toByteArray()
    }
}

internal fun detectTransferFormat(name: String?, mime: String?, bytes: ByteArray): TransferFormat {
    val start = bytes.take(128).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
    if (start.startsWith("#EXTM3U")) return TransferFormat.M3U8
    if (start.startsWith("{")) return TransferFormat.JSON
    if (start.startsWith("schemaVersion,kind,")) return TransferFormat.CSV
    when (name?.substringAfterLast('.', "")?.lowercase()) {
        "json" -> return TransferFormat.JSON
        "csv" -> return TransferFormat.CSV
        "m3u", "m3u8" -> return TransferFormat.M3U8
    }
    return when (mime?.lowercase()) {
        "application/json", "text/json" -> TransferFormat.JSON
        "text/csv", "application/csv" -> TransferFormat.CSV
        "audio/x-mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-m3u", "application/vnd.apple.mpegurl" -> TransferFormat.M3U8
        else -> throw TransferException("Unrecognized import format")
    }
}
