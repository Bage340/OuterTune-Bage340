package com.dd3boh.outertune.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

internal fun copyRestoreEntry(input: InputStream, output: OutputStream, maxBytes: Long) {
    require(maxBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        if (total > maxBytes) {
            throw IOException("Backup entry exceeds the allowed size")
        }
        output.write(buffer, 0, count)
    }
}

internal data class RestoreFileReplacement(
    val stagedFile: File,
    val targetFile: File,
)

/** Installs every replacement atomically as one transaction, rolling all earlier files back on failure. */
internal fun installRestoredFiles(replacements: List<RestoreFileReplacement>) {
    require(replacements.map { it.targetFile.canonicalFile }.distinct().size == replacements.size) {
        "Restore targets must be unique"
    }
    val installed = mutableListOf<InstalledFile>()
    try {
        replacements.forEach { replacement ->
            installed += beginInstall(replacement.stagedFile, replacement.targetFile)
        }
    } catch (installFailure: Exception) {
        installed.asReversed().forEach { file ->
            runCatching(file::rollback).onFailure(installFailure::addSuppressed)
        }
        throw installFailure
    }
    installed.forEach(InstalledFile::commit)
}

private fun beginInstall(stagedFile: File, targetFile: File): InstalledFile {
    if (!stagedFile.isFile) {
        throw IOException("Staged restore file is missing: ${stagedFile.name}")
    }
    if (stagedFile.canonicalFile.parentFile != targetFile.canonicalFile.parentFile) {
        throw IOException("Staged and target restore files must share a directory")
    }

    val rollbackFile = File(targetFile.parentFile, targetFile.name + ".restore-backup")
    if (rollbackFile.exists() && !rollbackFile.delete()) {
        throw IOException("Unable to clear previous restore rollback file")
    }

    val originalMoved = targetFile.exists()
    if (originalMoved && !targetFile.renameTo(rollbackFile)) {
        throw IOException("Unable to preserve the current file: ${targetFile.name}")
    }

    try {
        if (!stagedFile.renameTo(targetFile)) {
            throw IOException("Unable to install the restored file: ${targetFile.name}")
        }
    } catch (installFailure: Exception) {
        if (originalMoved && !rollbackFile.renameTo(targetFile)) {
            installFailure.addSuppressed(IOException("Unable to roll back ${targetFile.name}"))
        }
        throw installFailure
    }

    return InstalledFile(targetFile, rollbackFile, originalMoved)
}

private class InstalledFile(
    private val targetFile: File,
    private val rollbackFile: File,
    private val originalMoved: Boolean,
) {
    fun rollback() {
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Unable to remove failed restore file: ${targetFile.name}")
        }
        if (originalMoved && !rollbackFile.renameTo(targetFile)) {
            throw IOException("Unable to roll back ${targetFile.name}")
        }
    }

    fun commit() {
        if (rollbackFile.exists() && !rollbackFile.delete()) {
            rollbackFile.deleteOnExit()
        }
    }
}
