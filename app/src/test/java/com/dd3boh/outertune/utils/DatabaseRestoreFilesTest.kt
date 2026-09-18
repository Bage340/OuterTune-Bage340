package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DatabaseRestoreFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installReplacesDatabaseAndRemovesRollbackCopy() {
        val target = temporaryFolder.newFile("song.db").apply { writeText("old") }
        val staged = temporaryFolder.newFile("probe_song.db").apply { writeText("new") }

        installRestoredDatabase(staged, target)

        assertEquals("new", target.readText())
        assertFalse(staged.exists())
        assertFalse(temporaryFolder.root.resolve("song.db.restore-backup").exists())
    }

    @Test
    fun missingStagedDatabaseLeavesCurrentDatabaseUntouched() {
        val target = temporaryFolder.newFile("song.db").apply { writeText("current") }
        val missing = temporaryFolder.root.resolve("missing.db")

        assertThrows(IOException::class.java) {
            installRestoredDatabase(missing, target)
        }

        assertEquals("current", target.readText())
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
}
