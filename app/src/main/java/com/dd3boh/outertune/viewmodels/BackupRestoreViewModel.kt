package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.extensions.zipOutputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.deleteDatabaseFiles
import com.dd3boh.outertune.utils.deleteDatabaseSidecars
import com.dd3boh.outertune.utils.installRestoredDatabase
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    // TODO: make these calls non-blocking
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()
    fun backup(uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openOutputStream(uri)?.use {
                it.buffered().zipOutputStream().use { outputStream ->
                    outputStream.setLevel(Deflater.BEST_COMPRESSION)
                    (context.filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered().use { inputStream ->
                        outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                        inputStream.copyTo(outputStream)
                    }
                    runBlocking(Dispatchers.IO) {
                        database.checkpoint()
                    }
                    FileInputStream(database.openHelper.writableDatabase.path).use { inputStream ->
                        outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }.onSuccess {
            Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            reportException(it)
            Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun restore(uri: Uri) {
        var restartRequired = false
        val result = runCatching {
            val stagedDatabase = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
            val stagedSettings = context.cacheDir.resolve("restore-$SETTINGS_FILENAME")
            stagedDatabase.parentFile?.mkdirs()
            deleteDatabaseFiles(stagedDatabase)
            if (stagedSettings.exists() && !stagedSettings.delete()) {
                throw IOException("Unable to clear staged settings")
            }

            var databaseFound = false
            var settingsFound = false
            val backupStream = context.applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open backup")
            backupStream.use {
                it.zipInputStream().use { inputStream ->
                    var entry = inputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            SETTINGS_FILENAME -> {
                                if (settingsFound) throw IOException("Duplicate settings in backup")
                                settingsFound = true
                                stagedSettings.outputStream().use(inputStream::copyTo)
                            }

                            InternalDatabase.DB_NAME -> {
                                if (databaseFound) throw IOException("Duplicate database in backup")
                                databaseFound = true
                                FileOutputStream(stagedDatabase).use(inputStream::copyTo)
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }

            if (!databaseFound || !validateStagedDatabase(stagedDatabase)) {
                deleteDatabaseFiles(stagedDatabase)
                if (stagedSettings.exists() && !stagedSettings.delete()) {
                    Log.w(TAG, "Unable to delete staged settings after validation failure")
                }
                return@runCatching RestoreResult.INCOMPATIBLE
            }

            Log.i(TAG, "Validated database backup; starting restore")
            val targetDatabase = File(
                requireNotNull(database.openHelper.writableDatabase.path) {
                    "Open database has no filesystem path"
                }
            )
            runBlocking(Dispatchers.IO) { database.checkpoint() }
            database.close()
            restartRequired = true

            deleteDatabaseSidecars(targetDatabase)
            installRestoredDatabase(stagedDatabase, targetDatabase)

            if (settingsFound) {
                val settingsFile = context.filesDir / "datastore" / SETTINGS_FILENAME
                settingsFile.parentFile?.mkdirs()
                stagedSettings.inputStream().use { inputStream ->
                    settingsFile.outputStream().use(inputStream::copyTo)
                }
            }
            RestoreResult.SUCCESS
        }

        result.exceptionOrNull()?.let {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
        }
        if (result.getOrNull() == RestoreResult.INCOMPATIBLE) {
            Log.e(TAG, "Incompatible database, aborting restore")
            Toast.makeText(
                context,
                context.getString(R.string.err_restore_incompatible_database),
                Toast.LENGTH_SHORT
            ).show()
        }

        if (restartRequired) {
            val stopIntent = Intent(context, MusicService::class.java)
            context.stopService(stopIntent)
            val startIntent = Intent(context, MainActivity::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(startIntent)
            exitProcess(0)
        }
    }

    private fun validateStagedDatabase(stagedDatabase: File): Boolean {
        Log.i(TAG, "Testing restored database for compatibility")
        var probe: MusicDatabase? = null
        return try {
            probe = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
            val integrityOk = probe.openHelper.writableDatabase.isDatabaseIntegrityOk
            if (integrityOk) {
                runBlocking(Dispatchers.IO) { probe.checkpoint() }
            }
            integrityOk
        } catch (e: Exception) {
            Log.e(TAG, "DB validation failed", e)
            false
        } finally {
            probe?.close()
            if (stagedDatabase.exists()) {
                runCatching { deleteDatabaseSidecars(stagedDatabase) }
                    .onFailure { Log.w(TAG, "Unable to delete staged database sidecars", it) }
            }
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }

    private enum class RestoreResult { SUCCESS, INCOMPATIBLE }
}
