package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DatabaseRestoreFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installReplacesDatabaseAndRemovesRollbackCopy() {
        val target = temporaryFolder.newFile("song.db").apply { writeText("old") }
        val staged = temporaryFolder.newFile("probe_song.db").apply { writeText("new") }

        installRestoredFiles(listOf(RestoreFileReplacement(staged, target)))

        assertEquals("new", target.readText())
        assertFalse(staged.exists())
        assertFalse(temporaryFolder.root.resolve("song.db.restore-backup").exists())
    }

    @Test
    fun missingStagedDatabaseLeavesCurrentDatabaseUntouched() {
        val target = temporaryFolder.newFile("song.db").apply { writeText("current") }
        val missing = temporaryFolder.root.resolve("missing.db")

        assertThrows(IOException::class.java) {
            installRestoredFiles(listOf(RestoreFileReplacement(missing, target)))
        }

        assertEquals("current", target.readText())
    }

    @Test
    fun laterInstallFailureRollsBackEarlierDatabaseReplacement() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("old-db") }
        val stagedDatabase = temporaryFolder.newFile("probe_song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply { writeText("old-settings") }
        val missingSettings = temporaryFolder.root.resolve("missing-settings")

        assertThrows(IOException::class.java) {
            installRestoredFiles(
                listOf(
                    RestoreFileReplacement(stagedDatabase, database),
                    RestoreFileReplacement(missingSettings, settings),
                )
            )
        }

        assertEquals("old-db", database.readText())
        assertEquals("old-settings", settings.readText())
        assertFalse(temporaryFolder.root.resolve("song.db.restore-backup").exists())
    }

    @Test
    fun interruptedRestoreRecoversPreviousDatabaseBeforeOpeningRoom() {
        val database = temporaryFolder.root.resolve("song.db")
        val previousDatabase = temporaryFolder.newFile("song.db.restore-backup").apply {
            writeText("previous-db")
        }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply {
            writeText("previous-settings")
        }
        temporaryFolder.newFile("probe_song.db").writeText("staged-db")

        recoverInterruptedRestore(database, settings)

        assertEquals("previous-db", database.readText())
        assertEquals("previous-settings", settings.readText())
        assertFalse(previousDatabase.exists())
    }

    @Test
    fun interruptedRestoreRollsBackBothInstalledFiles() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply {
            writeText("new-settings")
        }
        temporaryFolder.newFile("song.db.restore-backup").writeText("previous-db")
        temporaryFolder.newFile("settings.preferences_pb.restore-backup").writeText("previous-settings")

        recoverInterruptedRestore(database, settings)

        assertEquals("previous-db", database.readText())
        assertEquals("previous-settings", settings.readText())
    }

    @Test
    fun recoveryFailureKeepsDatabaseRollbackUntilSettingsCanBeRecovered() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val databaseRollback = temporaryFolder.newFile("song.db.restore-backup").apply {
            writeText("previous-db")
        }
        val settings = temporaryFolder.newFolder("settings.preferences_pb")
        settings.resolve("locked-child").writeText("prevents replacing the directory")
        temporaryFolder.newFile("settings.preferences_pb.restore-backup").writeText("previous-settings")

        assertThrows(IOException::class.java) { recoverInterruptedRestore(database, settings) }

        assertEquals("previous-db", databaseRollback.readText())
        assertEquals("new-db", database.readText())
        assertEquals("previous-settings", temporaryFolder.root.resolve("settings.preferences_pb.restore-backup").readText())
    }

    @Test
    fun recoveringOldDatabaseDiscardsSidecarsBelongingToNewDatabase() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply { writeText("settings") }
        temporaryFolder.newFile("song.db.restore-backup").writeText("previous-db")
        val wal = temporaryFolder.newFile("song.db-wal").apply { writeText("new-db-writes") }
        val shm = temporaryFolder.newFile("song.db-shm")

        recoverInterruptedRestore(database, settings)

        assertEquals("previous-db", database.readText())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
    }

    @Test
    fun repeatedRecoveryCanFinishAfterSettingsWereAlreadyRecovered() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply { writeText("previous-settings") }
        temporaryFolder.newFile("song.db.restore-backup").writeText("previous-db")

        recoverInterruptedRestore(database, settings)
        recoverInterruptedRestore(database, settings)

        assertEquals("previous-db", database.readText())
        assertEquals("previous-settings", settings.readText())
    }

    @Test
    fun committedRestoreKeepsNewFilesWhenRollbackCleanupWasInterrupted() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply { writeText("new-settings") }
        temporaryFolder.newFile("song.db.restore-backup").writeText("previous-db")
        temporaryFolder.newFile("settings.preferences_pb.restore-backup").writeText("previous-settings")
        temporaryFolder.newFile("song.db.restore-committed").writeText("committed")

        recoverInterruptedRestore(database, settings)

        assertEquals("new-db", database.readText())
        assertEquals("new-settings", settings.readText())
        assertFalse(temporaryFolder.root.resolve("song.db.restore-backup").exists())
    }

    @Test
    fun interruptedRestoreRemovesSettingsThatDidNotPreviouslyExist() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("new-db") }
        val settings = temporaryFolder.newFile("settings.preferences_pb").apply { writeText("new-settings") }
        temporaryFolder.newFile("song.db.restore-backup").writeText("previous-db")
        temporaryFolder.newFile("settings.preferences_pb.restore-absent").writeText("absent")

        recoverInterruptedRestore(database, settings)

        assertEquals("previous-db", database.readText())
        assertFalse(settings.exists())
    }

    @Test
    fun installerDoesNotDiscardAnUnrecoveredPreviousDatabase() {
        val database = temporaryFolder.newFile("song.db").apply { writeText("current-db") }
        val rollback = temporaryFolder.newFile("song.db.restore-backup").apply {
            writeText("unrecovered-db")
        }
        val staged = temporaryFolder.newFile("probe_song.db").apply { writeText("new-db") }

        assertThrows(IOException::class.java) {
            installRestoredFiles(listOf(RestoreFileReplacement(staged, database)))
        }

        assertEquals("current-db", database.readText())
        assertEquals("unrecovered-db", rollback.readText())
        assertEquals("new-db", staged.readText())
    }

    @Test
    fun databaseCleanupRemovesWalAndSharedMemorySidecars() {
        val database = temporaryFolder.newFile("song.db")
        val wal = temporaryFolder.newFile("song.db-wal")
        val shm = temporaryFolder.newFile("song.db-shm")

        deleteDatabaseFiles(database)

        assertFalse(database.exists())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
    }

    @Test
    fun oversizedArchiveEntryIsRejectedBeforeWritingPastLimit() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            copyRestoreEntry(ByteArrayInputStream(ByteArray(9)), output, maxBytes = 8)
        }

        assertEquals(0, output.size())
    }

    @Test
    fun unavailableBackupOutputStreamIsAnError() {
        assertThrows(IOException::class.java) {
            requireBackupOutputStream(null)
        }
    }
}
