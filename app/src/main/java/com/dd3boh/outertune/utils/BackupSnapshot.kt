package com.dd3boh.outertune.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Room's write transaction keeps the main file and WAL at one committed snapshot. */
internal suspend fun writeBackupDatabase(database: MusicDatabase, output: ZipOutputStream) {
    database.withScannerTransaction {
        val databaseFile = File(requireNotNull(openHelper.writableDatabase.path))
        listOf(databaseFile to InternalDatabase.DB_NAME, File(databaseFile.path + "-wal") to "${InternalDatabase.DB_NAME}-wal")
            .forEach { (file, entryName) ->
                if (file == databaseFile || file.isFile) {
                    output.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
    }
}

/** Parse through the same serializer as live preferences before closing the current database. */
internal suspend fun validateRestoreSettings(file: File) {
    if (!file.isFile) throw IOException("Staged restore settings are missing")
    val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + job)
    try {
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).data.first()
    } finally {
        job.cancelAndJoin()
    }
}

internal fun requireCompleteCheckpoint(database: MusicDatabase) {
    database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
        if (!cursor.moveToFirst() || cursor.getInt(0) != 0 || cursor.getInt(1) != cursor.getInt(2)) {
            throw IOException("Unable to checkpoint all database writes safely")
        }
    }
}

/** Room must validate an existing backup, never initialize a new DB from an empty archive entry. */
internal fun validateBackupDatabase(context: Context, staged: File): Boolean {
    var probe: MusicDatabase? = null
    return try {
        val header = ByteArray(16)
        DataInputStream(staged.inputStream()).use { it.readFully(header) }
        if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
            throw IOException("Backup is not a SQLite database")
        }
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            if (sqlite.version !in 1..MusicDatabase.MUSIC_DATABASE_VERSION) {
                throw IOException("Backup database has an unsupported schema version")
            }
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'song'", null).use {
                if (!it.moveToFirst()) throw IOException("Backup database has no song table")
            }
        }
        probe = InternalDatabase.newTestInstance(context, staged.name)
        val sqlite = probe.openHelper.writableDatabase
        val valid = sqlite.isDatabaseIntegrityOk && sqlite.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }
        if (valid) requireCompleteCheckpoint(probe)
        valid
    } catch (e: Exception) {
        Log.w("BackupRestore", "Database backup validation failed", e)
        false
    } finally {
        probe?.close()
        deleteDatabaseSidecars(staged)
    }
}
