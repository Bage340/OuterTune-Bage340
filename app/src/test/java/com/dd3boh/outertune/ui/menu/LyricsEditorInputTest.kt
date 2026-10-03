package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.db.entities.LyricsEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEditorInputTest {
    @Test
    fun negativeCacheOpensAnEmptyEditor() {
        assertEquals("", lyricsEditorInput(LyricsEntity.LYRICS_NOT_FOUND))
    }

    @Test
    fun missingOrEmptyLyricsOpenAnEmptyEditor() {
        assertEquals("", lyricsEditorInput(null))
        assertEquals("", lyricsEditorInput(""))
    }

    @Test
    fun plainLyricsKeepWhitespaceAndLineEndings() {
        val lyrics = "  First line\r\nSecond line  \n"
        assertEquals(lyrics, lyricsEditorInput(lyrics))
        assertEquals(" \n\t", lyricsEditorInput(" \n\t"))
    }

    @Test
    fun syncedLyricsKeepTimestampsAndFormatting() {
        val lyrics = "[00:01.23] First line\n[00:02.45]Second line\n"
        assertEquals(lyrics, lyricsEditorInput(lyrics))
    }

    @Test
    fun sentinelMentionInsideRealLyricsRemainsEditable() {
        val lyrics = "A line mentioning LYRICS_NOT_FOUND\n"
        assertEquals(lyrics, lyricsEditorInput(lyrics))
    }
}
