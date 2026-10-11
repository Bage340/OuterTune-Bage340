package com.zionhuang.innertube.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.PlaylistPage
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatching {
    val page = getOrThrow()
    require(page.snapshotComplete) { "Incomplete playlist snapshot" }
    val songs = page.songs.toMutableList()
    val consumedTokens = mutableSetOf<String>()
    fun consume(token: String) {
        require(token.isNotBlank() && consumedTokens.add(token)) { "Invalid or repeated playlist continuation" }
        require(consumedTokens.size <= 1000) { "Playlist continuation limit exceeded" }
    }
    var continuation = page.songsContinuation
    while (continuation != null) {
        consume(continuation)
        val continuationPage = YouTube.playlistContinuation(continuation).getOrThrow()
        require(continuationPage.snapshotComplete) { "Incomplete playlist continuation" }
        songs += continuationPage.songs
        continuation = continuationPage.continuation
    }
    continuation = page.continuation
    while (continuation != null) {
        consume(continuation)
        continuation = YouTube.playlistSectionContinuation(continuation).getOrThrow().continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = null,
        continuation = null
    )
}.onFailure { if (it is CancellationException) throw it }

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    require(page.snapshotComplete) { "Incomplete library snapshot" }
    val items = page.items.toMutableList()
    var continuation = page.continuation
    val consumedTokens = mutableSetOf<String>()
    while (continuation != null) {
        require(continuation.isNotBlank() && consumedTokens.add(continuation)) { "Invalid or repeated library continuation" }
        val continuationPage = YouTube.libraryContinuation(continuation).getOrThrow()
        require(continuationPage.snapshotComplete) { "Incomplete library continuation" }
        items += continuationPage.items
        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = null
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .associate {
            val (key, value) = it.split("=")
            key to value
        }

fun String.parseTime(): Int? {
    try {
        val parts = split(":").map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
