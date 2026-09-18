package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScanPathPolicyTest {
    @Test
    fun directoryBoundaryDoesNotMatchSimilarlyNamedSibling() {
        assertTrue(isWithinScanDirectory("/storage/Music/song.flac", "/storage/Music/"))
        assertTrue(isWithinScanDirectory("/storage/Music", "/storage/Music"))
        assertFalse(isWithinScanDirectory("/storage/Music2/song.flac", "/storage/Music"))
        assertFalse(isWithinScanDirectory("/storage/song.flac", ""))
    }

    @Test
    fun emptyMediaStoreRootsQueryAllMusicWithoutDanglingParenthesis() {
        val query = mediaStoreScanSelection(emptyList())
        assertEquals("is_music != 0", query.selection)
        assertTrue(query.arguments.isEmpty())
    }

    @Test
    fun mediaStorePathsAreBoundAndWildcardsAreLiteral() {
        val query = mediaStoreScanSelection(listOf("/storage/Music_100%", "/storage/O'Brien"))
        assertEquals("is_music != 0 AND (_data LIKE ? ESCAPE '\\' OR _data LIKE ? ESCAPE '\\')", query.selection)
        assertEquals(listOf("/storage/Music\\_100\\%/%", "/storage/O'Brien/%"), query.arguments)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidConfiguredRootCannotBecomeAnUnrestrictedQuery() {
        mediaStoreScanSelection(listOf(""))
    }

    @Test
    fun tagLibWithoutRootsCannotStartButMediaStoreCan() {
        assertFalse(shouldScanLocalFiles(false, " \n "))
        assertTrue(shouldScanLocalFiles(true, ""))
        assertTrue(shouldScanLocalFiles(false, "content://provider/tree/music"))
    }

    @Test(expected = IOException::class)
    fun missingProviderResultAbortsInsteadOfRepresentingAnEmptyLibrary() {
        requireScanResult<List<String>>(null, "music query")
    }

    @Test
    fun successfulEmptyProviderResultRemainsValid() {
        assertTrue(requireScanResult(emptyList<String>(), "music query").isEmpty())
    }

    @Test
    fun emptyScanCannotReconcileAnExistingLocalLibrary() {
        assertFalse(shouldReconcileScan(resultCount = 0, existingLocalSongCount = 1))
        assertTrue(shouldReconcileScan(resultCount = 0, existingLocalSongCount = 0))
        assertTrue(shouldReconcileScan(resultCount = 1, existingLocalSongCount = 3))
    }
}
