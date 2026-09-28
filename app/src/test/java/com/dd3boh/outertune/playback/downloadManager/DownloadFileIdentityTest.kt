package com.dd3boh.outertune.playback.downloadManager

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadFileIdentityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun exactMediaIdComesFromTheFinalBracketPair() {
        assertEquals("R4v3-B0y", mediaIdFromDownloadFileName("S3RL [live] [R4v3-B0y].mka"))
    }

    @Test
    fun malformedNamesAreNotIndexed() {
        assertNull(mediaIdFromDownloadFileName(null))
        assertNull(mediaIdFromDownloadFileName("track.mka"))
        assertNull(mediaIdFromDownloadFileName("track [].mka"))
        assertNull(mediaIdFromDownloadFileName("notes [R4v3-B0y].bak"))
        assertNull(mediaIdFromDownloadFileName("notes[R4v3-B0y].mka"))
        assertNull(mediaIdFromDownloadFileName("notes [R4v3-B0y].mka.bak"))
    }

    @Test
    fun emptyAndDeletedFilesDoNotHideDownloadCandidates() {
        val empty = temporaryFolder.newFile("Empty [abc].mka")
        val usable = temporaryFolder.newFile("Usable [abc].mka")
        usable.writeText("audio data")
        val deleted = temporaryFolder.newFile("Gone [gone].mka")
        val documents = listOf(empty, usable, deleted).map(DocumentFile::fromFile)
        deleted.delete()

        val indexed = indexDownloadFiles(documents)

        assertEquals(usable.name, indexed["abc"]?.name)
        assertTrue("gone" !in indexed)
    }

    @Test
    fun savedFileNameRemovesPathAndControlCharactersButRetainsExactId() {
        val name = buildDownloadFileName("../bad\\track/\u0000\u202E${"a".repeat(300)}", "R4v3-B0y")

        assertEquals("R4v3-B0y", mediaIdFromDownloadFileName(name))
        assertTrue(name.length <= 120)
        assertTrue(".." !in name)
        assertTrue('/' !in name && '\\' !in name)
        assertTrue(name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test
    fun unsafeMediaIdCannotBeEncodedAsAReimportableFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            buildDownloadFileName("Title", "../different]")
        }
    }

    @Test
    fun previewFilesCannotBeIndexedOrDeletedByStableAndViceVersa() {
        val stable = temporaryFolder.newFile(buildDownloadFileName("Song", "AbCdEf12345"))
        val preview = temporaryFolder.newFile(buildDownloadFileName("Song", "AbCdEf12345", previewChannel = true))
        stable.writeText("stable audio")
        preview.writeText("preview audio")

        assertEquals(stable.name, indexDownloadFiles(listOf(DocumentFile.fromFile(stable), DocumentFile.fromFile(preview)))["AbCdEf12345"]?.name)
        assertEquals(preview.name, indexDownloadFiles(listOf(DocumentFile.fromFile(stable), DocumentFile.fromFile(preview)), previewChannel = true)["AbCdEf12345"]?.name)
        assertNull(mediaIdFromDownloadFileName(preview.name))
        assertNull(mediaIdFromDownloadFileName(stable.name, previewChannel = true))
    }

    @Test
    fun nonemptyInterruptedPendingFileIsNeverIndexedAsComplete() {
        val pending = temporaryFolder.newFile("pending-deadbeef.partial")
        pending.writeText("truncated but non-empty audio")

        assertTrue(indexDownloadFiles(listOf(DocumentFile.fromFile(pending))).isEmpty())
        assertNull(mediaIdFromDownloadFileName(pending.name))
    }
}
