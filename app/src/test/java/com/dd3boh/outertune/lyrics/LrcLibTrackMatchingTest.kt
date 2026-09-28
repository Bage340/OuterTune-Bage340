package com.dd3boh.outertune.lyrics

import com.dd3boh.lrclib.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibTrackMatchingTest {
    @Test fun matchedTimedCandidateBeatsPlainCandidate() {
        val tracks = listOf(
            track(1, "The Song", "The Artist", 201.0, plain = "plain"),
            track(2, "The Song", "The Artist", 203.0, synced = "[00:01.00]timed")
        )

        assertEquals("[00:01.00]timed", selectLrcLibLyrics(tracks, "the song", "The Artist", 202))
    }

    @Test fun wrongDurationCandidateIsRejected() {
        assertNull(selectLrcLibLyrics(listOf(track(1, "Song", "Artist", 320.0, synced = "timed")), "Song", "Artist", 200))
    }

    @Test fun remixTimedLyricsCannotReplaceStudioPlainLyrics() {
        val tracks = listOf(
            track(1, "Song (Remix)", "Artist", 201.0, synced = "remix timed"),
            track(2, "Song", "Artist", 200.0, plain = "studio plain")
        )

        assertEquals("studio plain", selectLrcLibLyrics(tracks, "Song", "Artist", 200))
    }

    @Test fun liveAndCoverCandidatesAreRejected() {
        val tracks = listOf(
            track(1, "Song Live", "Artist", 200.0, synced = "live"),
            track(2, "Song", "Cover Artist", 200.0, synced = "cover")
        )

        assertNull(selectLrcLibLyrics(tracks, "Song", "Artist", 200))
    }

    @Test fun unknownDurationStillRequiresMatchingTitleAndArtist() {
        val tracks = listOf(
            track(1, "Different Song", "Artist", 200.0, synced = "wrong"),
            track(2, "Song", "Artist", 300.0, plain = "right")
        )

        assertEquals("right", selectLrcLibLyrics(tracks, "Song", "Artist", -1))
    }

    @Test fun knownAlbumRejectsDifferentAlbumWhenCandidateIdentifiesIt() {
        val tracks = listOf(
            track(1, "Song", "Artist", 200.0, synced = "wrong album", album = "Live Album"),
            track(2, "Song", "Artist", 201.0, plain = "right album", album = "Studio Album")
        )

        assertEquals("right album", selectLrcLibLyrics(tracks, "Song", "Artist", 200, "Studio Album"))
    }

    private fun track(id: Int, title: String, artist: String, duration: Double,
                      plain: String? = null, synced: String? = null, album: String? = null) =
        Track(id, title, artist, duration, plain, synced, album)
}
