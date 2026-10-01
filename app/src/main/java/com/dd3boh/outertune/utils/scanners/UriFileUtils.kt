package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import java.io.File

fun documentFileFromUri(context: Context, uris: List<Uri>): List<DocumentFile> {
    return uris.mapNotNull { customDocFileFromUri(context, it) }.filter { it.isDirectory }
}

fun documentFileFromUri(context: Context, uri: Uri): DocumentFile? {
    return customDocFileFromUri(context, uri)
}

fun stringFromUriList(uris: List<Uri>): String {
    if (uris.isEmpty()) return ""
    return uris.distinctBy { it.toString() }.joinToString("\n")
}

fun uriListFromString(str: String): List<Uri> {
    return str.split("\n").map { it.toUri() }.filter { it.toString().isNotBlank() }.distinctBy { it.toString() }
}

fun fileFromUri(context: Context, uri: Uri): File? {
    if (!DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) return null
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = if (uri.pathSegments.firstOrNull() == "document" || uri.pathSegments.getOrNull(2) == "document") {
        DocumentsContract.getDocumentId(uri)
    } else {
        DocumentsContract.getTreeDocumentId(uri)
    }
    val parts = docId.split(":", limit = 2)
    val rootId = parts[0]
    val relativePath = parts.getOrElse(1) { "" }
    val rootDir: File?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        rootDir = if (rootId.equals("primary", ignoreCase = true)) {
            storageManager.primaryStorageVolume.directory
        } else {
            storageManager.storageVolumes.firstOrNull {
                it.uuid != null && it.uuid.equals(rootId, ignoreCase = true)
            }?.directory
        }
    } else {
        rootDir = when (rootId.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> {
                // Try to handle secondary storage
                if (rootId.isBlank() || rootId.any { it == '/' || it == '\\' } || rootId == "." || rootId == "..") return null
                val secondaryStorage = "/storage/$rootId"
                if (File(secondaryStorage).exists()) {
                    File(secondaryStorage)
                } else {
                    null
                }
            }
        }
    }
    val root = rootDir?.canonicalFile ?: return null
    val file = if (relativePath.isEmpty()) root else File(root, relativePath).canonicalFile
    return file.takeIf { it == root || it.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) }
}

fun absoluteFilePathFromUri(context: Context, uri: Uri): String? {
    return fileFromUri(context, uri)?.absolutePath
}

private fun customDocFileFromUri(context: Context, uri: Uri): DocumentFile? = when {
    DocumentsContract.isTreeUri(uri) -> TreeDocumentFileOt(
        null, context, DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
    )
    DocumentsContract.isDocumentUri(context, uri) -> DocumentFile.fromSingleUri(context, uri)
    else -> null
}
