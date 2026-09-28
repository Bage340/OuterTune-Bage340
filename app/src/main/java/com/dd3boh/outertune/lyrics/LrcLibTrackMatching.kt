package com.dd3boh.outertune.lyrics

import com.dd3boh.lrclib.models.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

private const val DURATION_TOLERANCE_SECONDS = 8.0

private fun normalizedTrackText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

/** Keep version markers such as remix or live in the title; stripping them can attach wrong lyrics. */
internal fun selectLrcLibLyrics(
    candidates: List<Track>,
    title: String,
    artist: String,
    duration: Int,
    album: String? = null,
): String? {
    val wantedTitle = normalizedTrackText(title)
    val wantedArtist = normalizedTrackText(artist)
    val wantedAlbum = album?.takeIf { it.isNotBlank() }?.let(::normalizedTrackText)
    if (wantedTitle.isEmpty() || wantedArtist.isEmpty()) return null

    val matches = candidates.filter { track ->
        normalizedTrackText(track.trackName) == wantedTitle &&
            normalizedTrackText(track.artistName) == wantedArtist &&
            (duration <= 0 || abs(track.duration - duration) <= DURATION_TOLERANCE_SECONDS) &&
            (wantedAlbum == null || track.albumName.isNullOrBlank() ||
                track.albumName?.let(::normalizedTrackText) == wantedAlbum)
    }
    val byDuration = if (duration > 0) matches.sortedBy { abs(it.duration - duration) } else matches
    return byDuration.firstNotNullOfOrNull { it.syncedLyrics?.takeIf(String::isNotBlank) }
        ?: byDuration.firstNotNullOfOrNull { it.plainLyrics?.takeIf(String::isNotBlank) }
}
