package com.dd3boh.outertune.utils.scanners

import androidx.documentfile.provider.DocumentFile
import java.io.IOException

data class MediaStoreScanSelection(
    val selection: String,
    val arguments: List<String>,
)

/**
 * Returns whether [path] is [directory] itself or a child of it. Paths are compared at a
 * directory boundary so a root named `Music` never includes a sibling named `Music2`.
 */
fun isWithinScanDirectory(path: String, directory: String): Boolean {
    val normalizedPath = path.trimEnd('/')
    val normalizedDirectory = directory.trim().trimEnd('/')
    if (normalizedDirectory.isEmpty()) return false
    return normalizedPath == normalizedDirectory || normalizedPath.startsWith("$normalizedDirectory/")
}

/** Builds a MediaStore selection without allowing user paths to alter a LIKE expression. */
fun mediaStoreScanSelection(scanRoots: List<String>): MediaStoreScanSelection {
    val normalizedRoots = scanRoots.map { root ->
        root.trim().trimEnd('/').also {
            require(it.isNotEmpty()) { "A MediaStore scan root must not be empty" }
        }
    }
    if (normalizedRoots.isEmpty()) {
        return MediaStoreScanSelection("is_music != 0", emptyList())
    }

    val predicates = normalizedRoots.joinToString(" OR ") { "_data LIKE ? ESCAPE '\\'" }
    val arguments = normalizedRoots.map { root -> "${escapeLikeLiteral(root)}/%" }
    return MediaStoreScanSelection("is_music != 0 AND ($predicates)", arguments)
}

/** TagLib needs a user-selected document tree; MediaStore can intentionally scan all audio. */
fun shouldScanLocalFiles(usesMediaStore: Boolean, configuredScanPaths: String): Boolean =
    usesMediaStore || configuredScanPaths.isNotBlank()

/** A provider returning null is a failure, never an empty media-library result. */
fun <T> requireScanResult(result: T?, operation: String): T =
    result ?: throw IOException("$operation returned no result")

/** An empty discovery result is unsafe to reconcile over an existing local library. */
fun shouldReconcileScan(resultCount: Int, existingLocalSongCount: Int): Boolean =
    resultCount > 0 || existingLocalSongCount == 0

fun requireSafeReconciliation(
    resultCount: Int,
    existingLocalSongCount: Int,
    source: String,
) {
    if (!shouldReconcileScan(resultCount, existingLocalSongCount)) {
        throw ScannerAbortException(
            "$source returned no songs; existing local library was left unchanged"
        )
    }
}

/** A result from one configured tree cannot stand in for another unavailable tree. */
fun requireAvailableScanRoots(roots: List<DocumentFile>) {
    roots.forEach { root ->
        val available = try {
            root.exists() && root.isDirectory && root.canRead()
        } catch (e: Exception) {
            throw ScannerAbortException("Could not read selected scan directory: ${root.uri}", e)
        }
        if (!available) {
            throw ScannerAbortException("Could not read selected scan directory: ${root.uri}")
        }
    }
}

private fun escapeLikeLiteral(value: String): String =
    value.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
