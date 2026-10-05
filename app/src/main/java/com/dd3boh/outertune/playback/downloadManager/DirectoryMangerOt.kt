package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    private val fileIndexLock = Any()
    private var configuredDirs: List<Uri> = emptyList()
    private var availableFiles: Set<DocumentFile> = emptySet()
    private var availableFilesById: Map<String, DocumentFile> = emptyMap()
    private val previewChannel = BuildConfig.FLAVOR.startsWith("preview")

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager (directory configured=${dir != Uri.EMPTY})")
        this.context = context
        this.dir = dir
        configuredDirs = (listOf(dir) + extraDirs).filter { it != Uri.EMPTY }.distinct()
        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            if (extraDirs.isNotEmpty()) {
                newAllDirs.addAll(
                    documentFileFromUri(context, configuredDirs.filterNot { it == dir }).filter { it.isDirectory }
                )
            }
            allDirs = newAllDirs.toList()
            replaceFileIndex(emptyList())
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
            replaceFileIndex(emptyList())
//            reportException(e)
//            Toast.makeText(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG).show()
            // TODO: snackbar for failed uri or not set up?
        }
    }

    fun deleteFile(mediaId: String): Boolean {
        val file = isExists(mediaId)
        val deleted = file?.delete() == true
        if (deleted) {
            synchronized(fileIndexLock) {
                availableFiles = availableFiles - file
                availableFilesById = availableFilesById - mediaId
            }
        }
        return deleted
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val resolver = context.contentResolver
        val directory = mainDir ?: throw IOException("Invalid directory")

        if (!directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val fileName = buildDownloadFileName(displayName, mediaId, previewChannel)
        // A final .mka name must appear only after the copy has completed. Otherwise a
        // process kill leaves a non-empty partial file that a later scan mistakes for a download.
        val newFile = directory.createFile("application/octet-stream", "pending-${UUID.randomUUID()}.partial")

        newFile?.let { pendingFile ->
            try {
                val output = resolver.openOutputStream(pendingFile.uri)
                    ?: throw IOException("Unable to open pending download file")
                output.use { out ->
                    input.copyTo(out)
                }
                if (!pendingFile.renameTo(fileName) || mediaIdFromDownloadFileName(pendingFile.name, previewChannel) != mediaId) {
                    throw IOException("Unable to publish completed download file")
                }
                addToFileIndex(mediaId, pendingFile)
                return pendingFile.uri
            } catch (error: Exception) {
                pendingFile.delete()
                throw error
            }
        }

        return null
    }

    fun isExists(mediaId: String): DocumentFile? {
        val file = synchronized(fileIndexLock) { availableFilesById[mediaId] } ?: return null
        if (isUsableDownloadFile(file)) return file
        synchronized(fileIndexLock) {
            availableFiles = availableFiles - file
            availableFilesById = availableFilesById - mediaId
        }
        return null
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false)
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
        if (useCache) {
            return synchronized(fileIndexLock) {
                availableFilesById.mapValues { it.value.uri }
            }
        }

        ensureDirectoriesReadable()
        val result = ArrayList<DocumentFile>()
        for (dir in allDirs) {
            scanDfRecursive(dir, result, scanHidden = true, failFast = true)
        }
        val indexed = result.mapNotNull { file ->
            val id = mediaIdFromDownloadFileName(file.name, previewChannel) ?: return@mapNotNull null
            if (isValidatedDownloadFile(file)) id to file else null
        }.distinctBy { it.first }.toMap()
        synchronized(fileIndexLock) {
            availableFiles = result.toSet()
            availableFilesById = indexed
        }
        return synchronized(fileIndexLock) {
            availableFilesById.mapValues { it.value.uri }
        }
    }

    fun getValidatedFilePathIfExists(mediaId: String): Uri? {
        val file = synchronized(fileIndexLock) { availableFilesById[mediaId] } ?: return null
        return file.uri.takeIf { isValidatedDownloadFile(file) }
    }

    private fun isValidatedDownloadFile(file: DocumentFile): Boolean {
        if (file is TreeDocumentFileOt) {
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val cursor = context.contentResolver.query(file.uri, columns, null, null, null)
                ?: throw IOException("Downloaded file metadata is unavailable")
            cursor.use {
                if (!it.moveToFirst() || it.isNull(0) || it.isNull(1)) {
                    throw IOException("Downloaded file metadata is incomplete")
                }
                if (!file.canRead()) throw IOException("Downloaded file is unreadable")
                val size = it.getLong(1)
                if (it.getString(0).isNullOrBlank() || size < 0) {
                    throw IOException("Downloaded file metadata is invalid")
                }
                return it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR && size > 0
            }
        }
        if (!file.canRead()) throw IOException("Downloaded file is unreadable")
        return isUsableDownloadFile(file)
    }

    fun ensureDirectoriesReadable() {
        if (configuredDirs.isNotEmpty() && configuredDirs.size != allDirs.size) {
            throw IOException("A configured download directory is unavailable")
        }
        for (directory in allDirs) {
            if (!directory.exists() || !directory.isDirectory || !directory.canRead()) {
                throw IOException("A download directory is unreadable")
            }
        }
    }

    fun getMainDlStorageUsage(): Long {
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getTotalDlStorageUsage(): Long {
        if (allDirs.isEmpty()) return 0
        return synchronized(fileIndexLock) { availableFiles.sumOf { it.length() } }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    private fun addToFileIndex(mediaId: String, file: DocumentFile) {
        synchronized(fileIndexLock) {
            availableFiles = availableFiles + file
            availableFilesById = availableFilesById + (mediaId to file)
        }
    }

    private fun replaceFileIndex(files: Collection<DocumentFile>) {
        val indexedFiles = indexDownloadFiles(files, previewChannel)
        synchronized(fileIndexLock) {
            availableFiles = files.toSet()
            availableFilesById = indexedFiles
        }
    }

}

internal fun isUsableDownloadFile(file: DocumentFile): Boolean =
    file.isFile && file.exists() && file.canRead() && file.length() > 0L

internal fun indexDownloadFiles(files: Collection<DocumentFile>, previewChannel: Boolean = false): Map<String, DocumentFile> =
    files.asSequence()
        .filter(::isUsableDownloadFile)
        .mapNotNull { file -> mediaIdFromDownloadFileName(file.name, previewChannel)?.let { it to file } }
        .distinctBy { it.first }
        .toMap()

private val SAFE_DOWNLOAD_MEDIA_ID = Regex("[A-Za-z0-9_-]{1,64}")
private const val PREVIEW_FILE_ID_PREFIX = "otpreview-"

internal fun buildDownloadFileName(displayName: String?, mediaId: String, previewChannel: Boolean = false): String {
    require(SAFE_DOWNLOAD_MEDIA_ID.matches(mediaId)) { "Invalid download media ID" }
    val fileId = if (previewChannel) PREVIEW_FILE_ID_PREFIX + mediaId else mediaId
    val maxTitleLength = 120 - fileId.length - " [].mka".length
    val title = displayName.orEmpty()
        .map { character ->
            when {
                character in "\\/:*?\"<>|" || character.isISOControl() ||
                    Character.getType(character) == Character.FORMAT.toInt() -> '_'
                else -> character
            }
        }
        .joinToString("")
        .replace("..", "_")
        .trim(' ', '.', '_')
        .take(maxTitleLength)
        .ifBlank { "Download" }
    return "$title [$fileId].mka"
}

internal fun mediaIdFromDownloadFileName(fileName: String?, previewChannel: Boolean = false): String? {
    val name = fileName ?: return null
    if (!name.endsWith(".mka", ignoreCase = true)) return null
    val stem = name.dropLast(4)
    if (!stem.endsWith(']')) return null
    val openingBracket = stem.lastIndexOf(" [")
    if (openingBracket < 0 || openingBracket + 2 >= stem.lastIndex) return null
    val fileId = stem.substring(openingBracket + 2, stem.lastIndex)
    val mediaId = if (previewChannel) fileId.removePrefix(PREVIEW_FILE_ID_PREFIX) else fileId
    if (previewChannel != fileId.startsWith(PREVIEW_FILE_ID_PREFIX)) return null
    return mediaId.takeIf(SAFE_DOWNLOAD_MEDIA_ID::matches)
}
