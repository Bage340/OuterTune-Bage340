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
}
