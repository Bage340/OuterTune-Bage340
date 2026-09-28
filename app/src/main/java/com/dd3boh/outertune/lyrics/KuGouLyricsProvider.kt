package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.kugou.KuGou

object KuGouLyricsProvider : LyricsProvider {
    override val id = "kugou"
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int, album: String?): LyricsFetchResult =
        // KuGou strips parenthesized qualifiers from the search keyword, then returns no candidate
        // title or artist to validate. Treat a versioned search as inconclusive, not as an absence.
        if (requiresVersionSensitiveMatching(title))
            LyricsFetchResult.Failed(IllegalArgumentException("KuGou cannot verify a versioned title"))
        else KuGou.getLyrics(title, artist, duration).toFetchResult()

    override suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        KuGou.getAllPossibleLyricsOptions(title, artist, duration, callback)
    }
}

internal fun requiresVersionSensitiveMatching(title: String): Boolean =
    Regex("\\b(remix|remaster(?:ed)?|live|sped[ -]*up|slowed|cover|acoustic|instrumental|radio[ -]*edit)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(title)
