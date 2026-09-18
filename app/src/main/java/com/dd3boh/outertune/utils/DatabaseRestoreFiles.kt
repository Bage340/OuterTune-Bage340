package com.dd3boh.outertune.utils

import java.io.File
import java.io.IOException

internal fun deleteDatabaseFiles(databaseFile: File) {
    deleteRequired(databaseFile)
    deleteDatabaseSidecars(databaseFile)
}

internal fun deleteDatabaseSidecars(databaseFile: File) {
    listOf(File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm")).forEach { file ->
        deleteRequired(file)
    }
}

private fun deleteRequired(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to delete staged database file: ${file.name}")
        }
}

/**
 * Replaces a closed database using same-directory renames and restores the original on failure.
 */
internal fun installRestoredDatabase(stagedFile: File, targetFile: File) {
    if (!stagedFile.isFile) {
        throw IOException("Staged database is missing")
    }
    if (stagedFile.canonicalFile.parentFile != targetFile.canonicalFile.parentFile) {
        throw IOException("Staged and target databases must share a directory")
    }

    val rollbackFile = File(targetFile.parentFile, targetFile.name + ".restore-backup")
    if (rollbackFile.exists() && !rollbackFile.delete()) {
        throw IOException("Unable to clear previous database rollback file")
    }

    val originalMoved = targetFile.exists()
    if (originalMoved && !targetFile.renameTo(rollbackFile)) {
        throw IOException("Unable to preserve the current database")
    }

    try {
        if (!stagedFile.renameTo(targetFile)) {
            throw IOException("Unable to install the restored database")
        }
    } catch (installFailure: Exception) {
        if (originalMoved && !rollbackFile.renameTo(targetFile)) {
            installFailure.addSuppressed(IOException("Unable to roll back the current database"))
        }
        throw installFailure
    }

    if (rollbackFile.exists() && !rollbackFile.delete()) {
        rollbackFile.deleteOnExit()
    }
}
