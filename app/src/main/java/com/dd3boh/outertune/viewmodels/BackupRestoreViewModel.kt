package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.extensions.zipOutputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.RestoreFileReplacement
import com.dd3boh.outertune.utils.stageBackupArchive
import com.dd3boh.outertune.utils.deleteDatabaseFiles
import com.dd3boh.outertune.utils.deleteDatabaseSidecars
import com.dd3boh.outertune.utils.installRestoredFiles
import com.dd3boh.outertune.utils.requireBackupOutputStream
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.validateRestoreSettings
import com.dd3boh.outertune.utils.writeBackupDatabase
import com.dd3boh.outertune.utils.stopSettingsDataStoreForRestore
import com.dd3boh.outertune.utils.validateBackupDatabase
import com.dd3boh.outertune.utils.requireCompleteCheckpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()
    private val operationMutex = Mutex()
    fun backup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                var failure: Exception? = null
                try {
                    requireBackupOutputStream(context.applicationContext.contentResolver.openOutputStream(uri)).use {
                        it.buffered().zipOutputStream().use { outputStream ->
                            outputStream.setLevel(Deflater.BEST_COMPRESSION)
                            outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                            val settings = context.filesDir / "datastore" / SETTINGS_FILENAME
                            if (settings.isFile) settings.inputStream().use { it.copyTo(outputStream) }
                            outputStream.closeEntry()
                            writeBackupDatabase(database, outputStream)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                }
                withContext(Dispatchers.Main) {
                    if (failure == null) {
                        Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
                    } else {
                        reportException(requireNotNull(failure))
                        Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock { restoreFromBackup(uri) }
        }
    }

    private suspend fun restoreFromBackup(uri: Uri) {
        var restartRequired = false
        val result = runCatching {
            val stagedDatabase = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
            val settingsFile = context.filesDir / "datastore" / SETTINGS_FILENAME
            val settingsDirectory = requireNotNull(settingsFile.parentFile)
            settingsDirectory.mkdirs()
            val stagedSettings = settingsDirectory.resolve("restore-staged.preferences_pb")
            stagedDatabase.parentFile?.mkdirs()
            deleteDatabaseFiles(stagedDatabase)
            if (stagedSettings.exists() && !stagedSettings.delete()) {
                throw IOException("Unable to clear staged settings")
            }

            val backupStream = context.applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open backup")
            val restoreContext = currentCoroutineContext()
            val settingsFound = backupStream.use {
                it.zipInputStream().use { inputStream ->
                    stageBackupArchive(inputStream, stagedDatabase, stagedSettings) { restoreContext.ensureActive() }
                }
            }
            if (settingsFound) validateRestoreSettings(stagedSettings)

            if (!validateBackupDatabase(context, stagedDatabase)) {
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
            currentCoroutineContext().ensureActive()
            requireCompleteCheckpoint(database)
            withContext(NonCancellable) {
                restartRequired = true
                stopSettingsDataStoreForRestore()
                database.close()
                deleteDatabaseSidecars(targetDatabase)
                installRestoredFiles(
                    buildList {
                        add(RestoreFileReplacement(stagedDatabase, targetDatabase))
                        if (settingsFound) {
                            add(RestoreFileReplacement(stagedSettings, settingsFile))
                        }
                    }
                )
            }
            RestoreResult.SUCCESS
        }

        withContext(NonCancellable + Dispatchers.Main) {
            result.exceptionOrNull()?.takeUnless { it is CancellationException }?.let {
                reportException(it)
                Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
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
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }

    private enum class RestoreResult { SUCCESS, INCOMPATIBLE }
}
