package com.dd3boh.outertune.utils

import org.junit.Assert.*
import org.junit.Test

class PlaylistFoldersTest {
    @Test fun canonicalPathsAndRoot() {
        assertEquals("/", PlaylistFolders.canonical(""))
        assertEquals("/a/b/", PlaylistFolders.canonical("//a///b"))
        assertEquals("/", PlaylistFolders.parent("/"))
        assertEquals("/a/", PlaylistFolders.parent("/a/b/"))
        assertEquals("/日本語/", PlaylistFolders.child("/", "日本語"))
    }

    @Test fun childrenAreDistinctAndSegmentAware() {
        assertEquals(listOf("/a/b/", "/a/c/"), PlaylistFolders.children("/a/",
            listOf("/a/", "/a/b/", "/a/b/c/", "/a/c/", "/ab/d/")))
        assertEquals(emptyList<String>(), PlaylistFolders.children("/empty/", listOf("/empty/")))
        assertFalse(PlaylistFolders.contains("/a/", "/ab/"))
    }

    @Test fun subtreeRenamePreservesSegments() {
        assertEquals("/z/b/", PlaylistFolders.relocate("/a/b/", "/a/", "/z/"))
        assertEquals("/z/", PlaylistFolders.relocate("/a/", "/a/", "/z/"))
    }

    @Test fun relocationPlanRejectsMovingIntoOwnSubtreeAndExistingFolders() {
        val paths = listOf("/a/", "/a/b/", "/other/", "/target/", "/target/a/")

        assertNull(PlaylistFolders.relocationPlan("/a/", "/a/b/", paths))
        assertNull(PlaylistFolders.relocationPlan("/a/", "/target/", paths))
        assertEquals(
            mapOf("/a/" to "/other/a/", "/a/b/" to "/other/a/b/"),
            PlaylistFolders.relocationPlan("/a/", "/other/", paths)
        )
    }

    @Test fun relocationPlanRejectsCaseInsensitiveDuplicateDestination() {
        val paths = listOf("/source/", "/target/", "/target/Mixes/")

        assertNull(
            PlaylistFolders.relocationPlan(
                source = "/source/",
                destinationParent = "/target/",
                paths = paths,
                newName = "mixes",
            )
        )
    }

    @Test fun contentsIncludePersistentEmptyFoldersAndOnlyImmediatePlaylists() {
        val contents = PlaylistFolders.contents(
            parent = "/a/",
            playlistPaths = listOf("/", "/a/", "/a/b/", "/ab/"),
            folderPaths = listOf("/a/b/", "/a/empty/", "/ab/")
        )

        assertEquals(listOf(1), contents.playlistIndexes)
        assertEquals(listOf("/a/b/", "/a/empty/"), contents.folderPaths)
    }

    @Test fun deletingFolderCollapsesEntireSubtreeToItsParentOnly() {
        assertEquals("/a/", PlaylistFolders.pathAfterDelete("/a/b/", "/a/b/"))
        assertEquals("/a/", PlaylistFolders.pathAfterDelete("/a/b/c/", "/a/b/"))
        assertEquals("/ab/", PlaylistFolders.pathAfterDelete("/ab/", "/a/b/"))
    }

    @Test fun invalidFolderNamesAreRejected() {
        listOf("", " ", ".", "..", "a/b", " a", "a\n").forEach {
            assertFalse(it, PlaylistFolders.validName(it))
        }
        assertTrue(PlaylistFolders.validName("A_B%"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun traversalRejected() { PlaylistFolders.canonical("/a/../b/") }

    @Test(expected = IllegalArgumentException::class)
    fun unrelatedSubtreeRejected() { PlaylistFolders.relocate("/ab/", "/a/", "/z/") }
}
