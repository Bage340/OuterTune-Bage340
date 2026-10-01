package com.dd3boh.outertune.utils

import java.io.File
import java.io.FileOutputStream
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
        throw IOException("Unable to delete restore file: ${file.name}")
    }
}

internal fun copyRestoreEntry(input: InputStream, output: OutputStream, maxBytes: Long, checkCancelled: () -> Unit = {}) {
    require(maxBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        if (total > maxBytes) {
            throw IOException("Backup entry exceeds the allowed size")
        }
        output.write(buffer, 0, count)
    }
}

internal fun requireBackupOutputStream(output: OutputStream?): OutputStream =
    output ?: throw IOException("Unable to open backup destination")

internal data class RestoreFileReplacement(
    val stagedFile: File,
    val targetFile: File,
)

/** Recover an interrupted restore before either Room or DataStore opens their files. */
internal fun recoverInterruptedRestore(databaseFile: File, settingsFile: File) {
    val databaseRollback = rollbackFileFor(databaseFile)
    val settingsRollback = rollbackFileFor(settingsFile)
    val committed = commitFileFor(databaseFile)
    val recoveryOrder = listOf(settingsFile, databaseFile)
    if (committed.exists()) {
        if (!databaseFile.isFile ||
            ((settingsRollback.exists() || absentFileFor(settingsFile).exists()) && !settingsFile.isFile)) {
            throw IOException("Committed restore is missing an installed file")
        }
        recoveryOrder.forEach(::clearRollback)
        deleteRequired(committed)
    } else if (databaseRollback.exists() || absentFileFor(databaseFile).exists()) {
        recoveryOrder.forEach { target ->
            if (target == databaseFile) deleteDatabaseSidecars(databaseFile)
            val rollback = rollbackFileFor(target)
            if (rollback.exists()) restoreRollback(target, rollback)
            else if (absentFileFor(target).exists()) deleteRequired(target)
        }
        // Keep the database rollback as the recovery indicator until settings are also safe.
        recoveryOrder.forEach(::clearRollback)
    } else if (settingsRollback.exists()) {
        // Compatibility with the previous installer, which removed the DB rollback first.
        if (!databaseFile.isFile || !settingsFile.isFile) {
            throw IOException("Interrupted restore left an incomplete database or settings file")
        }
        deleteRequired(settingsRollback)
    }
    deleteRequired(File(committed.path + ".tmp"))
}

private fun restoreRollback(targetFile: File, rollbackFile: File) {
    val recoveryFile = File(targetFile.path + ".restore-recovery")
    FileOutputStream(recoveryFile).use { output ->
        rollbackFile.inputStream().use { it.copyTo(output) }
        output.fd.sync()
    }
    deleteRequired(targetFile)
    if (!recoveryFile.renameTo(targetFile)) {
        throw IOException("Unable to recover previous file: ${targetFile.name}")
    }
}

private fun rollbackFileFor(targetFile: File): File =
    File(targetFile.parentFile, targetFile.name + ".restore-backup")

private fun absentFileFor(targetFile: File): File = File(targetFile.path + ".restore-absent")
private fun commitFileFor(targetFile: File): File = File(targetFile.path + ".restore-committed")

private fun clearRollback(targetFile: File) {
    deleteRequired(rollbackFileFor(targetFile))
    deleteRequired(absentFileFor(targetFile))
    deleteRequired(File(targetFile.path + ".restore-recovery"))
}

private fun writeDurableMarker(file: File, value: String) {
    FileOutputStream(file).use { output ->
        output.write(value.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}

/** Installs every replacement atomically as one transaction, rolling all earlier files back on failure. */
internal fun installRestoredFiles(replacements: List<RestoreFileReplacement>) {
    require(replacements.isNotEmpty()) { "A restore must replace its database" }
    require(replacements.map { it.targetFile.canonicalFile }.distinct().size == replacements.size) {
        "Restore targets must be unique"
    }
    val committed = commitFileFor(replacements.first().targetFile)
    if (committed.exists()) throw IOException("Previous restore commit still needs recovery")
    replacements.forEach { (staged, target) ->
        if (rollbackFileFor(target).exists() || absentFileFor(target).exists()) {
            throw IOException("Previous restore rollback still needs recovery: ${target.name}")
        }
        if (!staged.isFile || staged.canonicalFile.parentFile != target.canonicalFile.parentFile) {
            throw IOException("Invalid staged restore file: ${staged.name}")
        }
    }
    try {
        replacements.forEach { replacement ->
            beginInstall(replacement.stagedFile, replacement.targetFile)
        }
        val temporaryCommit = File(committed.path + ".tmp")
        writeDurableMarker(temporaryCommit, "committed")
        if (!temporaryCommit.renameTo(committed)) throw IOException("Unable to commit restored files")
    } catch (installFailure: Exception) {
        runCatching {
            val targets = replacements.asReversed().map { it.targetFile }
            targets.forEach { target ->
                val rollback = rollbackFileFor(target)
                if (rollback.exists()) restoreRollback(target, rollback)
                else if (absentFileFor(target).exists()) deleteRequired(target)
            }
            targets.forEach(::clearRollback)
        }.onFailure(installFailure::addSuppressed)
        throw installFailure
    }
    replacements.forEach { clearRollback(it.targetFile) }
    deleteRequired(committed)
}

private fun beginInstall(stagedFile: File, targetFile: File) {
    if (!stagedFile.isFile) {
        throw IOException("Staged restore file is missing: ${stagedFile.name}")
    }
    if (stagedFile.canonicalFile.parentFile != targetFile.canonicalFile.parentFile) {
        throw IOException("Staged and target restore files must share a directory")
    }

    val rollbackFile = rollbackFileFor(targetFile)
    if (rollbackFile.exists()) {
        throw IOException("Previous restore rollback file still exists: ${rollbackFile.name}")
    }
    val absentFile = absentFileFor(targetFile)
    if (absentFile.exists()) throw IOException("Previous restore absence marker still needs recovery")
    FileOutputStream(stagedFile, true).use { it.fd.sync() }

    val originalMoved = targetFile.exists()
    if (originalMoved && !targetFile.renameTo(rollbackFile)) {
        throw IOException("Unable to preserve the current file: ${targetFile.name}")
    }
    if (!originalMoved) writeDurableMarker(absentFile, "absent")

    try {
        if (!stagedFile.renameTo(targetFile)) {
            throw IOException("Unable to install the restored file: ${targetFile.name}")
        }
    } catch (installFailure: Exception) {
        if (originalMoved && !rollbackFile.renameTo(targetFile)) {
            installFailure.addSuppressed(IOException("Unable to roll back ${targetFile.name}"))
        }
        if (!originalMoved) runCatching { deleteRequired(absentFile) }.onFailure(installFailure::addSuppressed)
        throw installFailure
    }

}
