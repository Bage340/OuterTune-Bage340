package com.dd3boh.outertune.lyrics

import android.content.Context
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException

object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val id = "youtube-subtitle"
    override val name = "YouTube Subtitle"
    override fun isEnabled(context: Context) = true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int, album: String?): LyricsFetchResult =
        YouTube.transcript(id).fold(
            onSuccess = { LyricsFetchResult.Found(it) },
            onFailure = ::classifySubtitleFailure
        )
}

internal fun classifySubtitleFailure(cause: Throwable): LyricsFetchResult {
    if (cause is CancellationException) throw cause
    // Only the two explicit transcript-absence signals are definitive. Authentication and parser
    // failures can also be IllegalStateException and must remain retryable.
    return if (cause is IllegalStateException &&
        (cause.message?.startsWith("No caption tracks available") == true ||
            cause.message?.startsWith("Empty transcript") == true)
    ) LyricsFetchResult.NotFound else LyricsFetchResult.Failed(cause)
}
