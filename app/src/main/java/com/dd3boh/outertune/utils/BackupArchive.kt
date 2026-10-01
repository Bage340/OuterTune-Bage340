package com.dd3boh.outertune.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Extracts only to caller-owned staging paths; never derives a path from an archive member. */
internal fun stageBackupArchive(
    input: ZipInputStream,
    database: File,
    settings: File,
    maxDatabaseBytes: Long = 4L * 1024 * 1024 * 1024,
    maxSettingsBytes: Long = 64L * 1024 * 1024,
    checkCancelled: () -> Unit = {},
): Boolean {
    val seen = mutableSetOf<String>()
    var entry = input.nextEntry
    while (entry != null) {
        checkCancelled()
        val destination = when (entry.name) {
            "song.db" -> database to maxDatabaseBytes
            "song.db-wal" -> File(database.path + "-wal") to maxDatabaseBytes
            "settings.preferences_pb" -> settings to maxSettingsBytes
            else -> throw IOException("Unsupported backup member")
        }
        if (!seen.add(entry.name)) throw IOException("Duplicate backup member")
        if (entry.isDirectory || entry.size > destination.second) throw IOException("Invalid backup member size or type")
        FileOutputStream(destination.first).use { output ->
            copyRestoreEntry(input, output, destination.second, checkCancelled)
            output.fd.sync()
        }
        entry = input.nextEntry
    }
    if ("song.db" !in seen) throw IOException("Backup database is missing")
    return "settings.preferences_pb" in seen
}
