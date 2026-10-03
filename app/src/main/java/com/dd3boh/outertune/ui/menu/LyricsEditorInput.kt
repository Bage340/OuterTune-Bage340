package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.db.entities.LyricsEntity

internal fun lyricsEditorInput(lyrics: String?): String =
    lyrics?.takeUnless { it == LyricsEntity.LYRICS_NOT_FOUND }.orEmpty()
