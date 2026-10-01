package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun legacyDatabaseOnlyAndDatabaseSettingsWalArchivesAreAccepted() {
        val db = temporaryFolder.root.resolve("probe.db")
        val settings = temporaryFolder.root.resolve("staged.preferences_pb")
        archive("song.db" to "database").use {
            assertFalse(stageBackupArchive(it, db, settings))
        }
        archive("song.db" to "new database", "song.db-wal" to "wal", "settings.preferences_pb" to "settings").use {
            assertTrue(stageBackupArchive(it, db, settings))
        }
        assertEquals("new database", db.readText())
        assertEquals("wal", temporaryFolder.root.resolve("probe.db-wal").readText())
        assertEquals("settings", settings.readText())
    }

    @Test fun unknownAndTraversalMembersAreRejectedBeforeLiveFilesChange() {
        for (member in listOf("unknown.bin", "../song.db", "/song.db", "nested/song.db")) {
            val live = temporaryFolder.root.resolve("live.db").apply { writeText("live database") }
            val db = temporaryFolder.root.resolve("probe.db")
            val settings = temporaryFolder.root.resolve("staged.preferences_pb")
            archive("song.db" to "staged database", member to "extra").use {
                assertThrows(IOException::class.java) { stageBackupArchive(it, db, settings) }
            }
            assertEquals("live database", live.readText())
        }
    }

    @Test fun declaredOrStreamingOversizeAndMissingDatabaseAreRejected() {
        val db = temporaryFolder.root.resolve("probe.db")
        val settings = temporaryFolder.root.resolve("staged.preferences_pb")
        archive("song.db" to "12345").use {
            assertThrows(IOException::class.java) { stageBackupArchive(it, db, settings, maxDatabaseBytes = 4) }
        }
        archive("settings.preferences_pb" to "12345").use {
            assertThrows(IOException::class.java) { stageBackupArchive(it, db, settings, maxSettingsBytes = 4) }
        }
        archive("settings.preferences_pb" to "settings").use {
            assertThrows(IOException::class.java) { stageBackupArchive(it, db, settings) }
        }
    }

    private fun archive(vararg entries: Pair<String, String>): ZipInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            for ((name, content) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }
}
