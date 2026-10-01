package com.dd3boh.outertune.utils

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.CorruptionException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupSnapshotTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun corruptPreferencesAreRejectedBeforeLiveFilesCanBeReplaced() {
        val staged = temporaryFolder.newFile("restore.preferences_pb").apply {
            writeBytes(byteArrayOf(0xff.toByte()))
        }
        assertThrows(CorruptionException::class.java) {
            runBlocking { validateRestoreSettings(staged) }
        }
    }

    @Test
    fun emptyValidPreferencesAreAccepted() = runBlocking {
        validateRestoreSettings(temporaryFolder.newFile("restore.preferences_pb"))
    }

    @Test
    fun emptyOrUnversionedBackupCannotInitializeANewLibrary() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val stagedName = "invalid-restore-${UUID.randomUUID()}.db"
        val staged = context.getDatabasePath(stagedName)
        try {
            staged.parentFile!!.mkdirs()
            staged.writeBytes(byteArrayOf())
            assertFalse(validateBackupDatabase(context, staged))
            SQLiteDatabase.openOrCreateDatabase(staged, null).use {
                it.execSQL("CREATE TABLE unrelated (id TEXT PRIMARY KEY)")
            }
            assertFalse(validateBackupDatabase(context, staged))
        } finally {
            context.deleteDatabase(stagedName)
        }
    }

    @Test
    fun futureVersionBackupIsRejectedWithoutChangingLiveLibrary() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val liveName = "live-restore-${UUID.randomUUID()}.db"
        val stagedName = "future-restore-${UUID.randomUUID()}.db"
        val live = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, liveName)
            .allowMainThreadQueries().build())
        try {
            live.insert(SongEntity("existing", "Existing library", localPath = null, liked = true))
            val staged = context.getDatabasePath(stagedName)
            SQLiteDatabase.openOrCreateDatabase(staged, null).use {
                it.version = MusicDatabase.MUSIC_DATABASE_VERSION + 1
                it.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY)")
            }

            assertFalse(validateBackupDatabase(context, staged))

            assertTrue(runBlocking { live.song("existing").first()!!.song.liked })
        } finally {
            live.close()
            context.deleteDatabase(liveName)
            context.deleteDatabase(stagedName)
        }
    }

    @Test
    fun snapshotIncludesCommittedWalRowsAndReopensWithIntegrity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sourceName = "snapshot-source-${UUID.randomUUID()}.db"
        val restoredName = "snapshot-restored-${UUID.randomUUID()}.db"
        val source = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, sourceName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries().build())
        var restored: MusicDatabase? = null
        try {
            source.insert(SongEntity(id = "wal-song", title = "Committed in WAL", localPath = null, liked = true))
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { writeBackupDatabase(source, it) }

            val entries = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    entries += entry.name
                    val suffix = entry.name.removePrefix(InternalDatabase.DB_NAME)
                    context.getDatabasePath(restoredName + suffix).outputStream().use { input.copyTo(it) }
                    entry = input.nextEntry
                }
            }
            assertTrue(entries.contains(InternalDatabase.DB_NAME))
            assertTrue(entries.contains(InternalDatabase.DB_NAME + "-wal"))
            assertFalse(entries.contains(InternalDatabase.DB_NAME + "-shm"))
            assertTrue(validateBackupDatabase(context, context.getDatabasePath(restoredName)))
            restored = InternalDatabase.newTestInstance(context, restoredName)
            assertTrue(restored.openHelper.writableDatabase.isDatabaseIntegrityOk)
            assertEquals("wal-song", restored.song("wal-song").first()!!.id)
            assertTrue(restored.song("wal-song").first()!!.song.liked)
            withContext(Dispatchers.IO) { requireNotNull(restored).checkpoint() }
        } finally {
            restored?.close()
            source.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(restoredName)
        }
    }
}
