package com.zionhuang.kugou

import com.zionhuang.kugou.models.SearchLyricsResponse
import com.zionhuang.kugou.models.SearchSongResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.SerializationException
import org.junit.Test

class KuGouCandidateMatchingTest {
    private fun song(title: String?, artist: String?, duration: Int, hash: String) =
        SearchSongResponse.Data.Info(duration = duration, hash = hash, songname = title, singername = artist)

    @Test fun hashSearchRejectsSameDurationWrongRecording() {
        val songs = listOf(
            song("Hello (Live)", "Adele", 295, "wrong-version"),
            song("Hello", "Other Artist", 295, "wrong-artist"),
            song("Hello", "Adele", 295, "correct"),
            song(null, null, 295, "unverifiable"),
        )

        assertEquals(listOf("correct"), matchingSongCandidates(songs, "Hello", "Adele", 295).map { it.hash })
    }

    @Test fun hashSearchRejectsUnverifiableHits() {
        val songs = listOf(song(null, "Adele", 200, "no-title"), song("Song", null, 200, "no-artist"))

        assertEquals(emptyList<String>(), matchingSongCandidates(songs, "Song", "Adele", 200).map { it.hash })
    }

    @Test fun responseMetadataMapsActualSongAndLyricsEndpointFields() {
        val json = Json { ignoreUnknownKeys = true }
        val songResponse = json.decodeFromString<SearchSongResponse>("""{"status":1,"errcode":0,"error":"","data":{"info":[{"duration":295,"hash":"fixture","songname":"Hello (Live)","singername":"Adele"}]}}""")
        val lyricsResponse = json.decodeFromString<SearchLyricsResponse>("""{"status":200,"info":"OK","errcode":200,"errmsg":"OK","expire":7200,"candidates":[{"id":"123","product_from":"official","duration":294974,"accesskey":"fixture","song":"Hello","singer":"Adele"}]}""")

        assertEquals("Hello (Live)", songResponse.data.info.single().songname)
        assertEquals("Adele", songResponse.data.info.single().singername)
        assertEquals("Hello", lyricsResponse.candidates.single().song)
        assertEquals("Adele", lyricsResponse.candidates.single().singer)
    }

    @Test fun matchingPreservesVersionQualifiersAndUnknownDurationStillRequiresIdentity() {
        val titles = listOf("Song", "Song (Live)", "Song (Remix)", "Song (Cover)", "Song (Sped Up)", "Song (Slowed)", "Song (Nightcore)", "Song (Radio Edit)")
        val songs = titles.map { song(it, "Artist", 200, it) }
        for (title in titles) {
            assertEquals(listOf(title), matchingSongCandidates(songs, title, "Artist", -1).map { it.hash })
        }
        assertEquals("Song (Live)", KuGou.generateKeyword("Song (Live)", "Artist").title)
    }

    @Test fun keywordCandidatesRequireTitleArtistAndDuration() {
        val candidates = listOf(
            candidate(1, 200_000).copy(song = "Other song", singer = "Artist"),
            candidate(2, 200_000).copy(song = "Song (Live)", singer = "Artist"),
            candidate(3, 200_000).copy(song = "Song", singer = "Other artist"),
            candidate(4, 200_000).copy(song = "Song", singer = "Artist"),
            candidate(5, 200_000),
        )
        assertEquals(listOf(4L), matchingKeywordCandidates(candidates, 200, "Song", "Artist").map { it.id })
    }

    @Test fun songMatchingUsesAlbumWhenAvailableAndAcceptsArtistDelimiterVariants() {
        val songs = listOf(
            song("Song", "Artist A、Artist B", 200, "right").copy(albumName = "Album"),
            song("Song", "Artist A、Artist B", 200, "wrong").copy(albumName = "Live Album"),
        )
        assertEquals(listOf("right"), matchingSongCandidates(songs, "Song", "Artist B, Artist A", 200, "Album").map { it.hash })
    }

    @Test fun songDurationBoundaryUsesSecondsWithoutIntegerOverflow() {
        val songs = listOf(song("Song", "Artist", 192, "lower"), song("Song", "Artist", 208, "upper"), song("Song", "Artist", 209, "outside"))
        assertEquals(listOf("lower", "upper"), matchingSongCandidates(songs, "Song", "Artist", 200).map { it.hash })
        assertEquals(emptyList<String>(), matchingSongCandidates(listOf(song("Song", "Artist", Int.MAX_VALUE, "overflow")), "Song", "Artist", -2).map { it.hash })
    }

    @Test fun identityDoesNotAssumeTranslatedSubtitlesAreEquivalent() {
        val songs = listOf(song("千年以后", "陈零九", 285, "canonical"))
        assertEquals(emptyList<String>(), matchingSongCandidates(songs, "千年以後 (After A Thousand Years)", "陳零九", 285).map { it.hash })
        assertEquals(listOf("canonical"), matchingSongCandidates(songs, "千年以后", "陈零九", 285).map { it.hash })
    }

    @Test fun normalizationPreservesActualSupportedLrcTimings() {
        val lrc = "[00:01.25]First\n[00:09.876]Second\n[01:02]Third\n[120:02:123]Fourth"
        assertEquals(lrc, KuGou.normalizeDownloadedLyrics(lrc))
        assertNull(KuGou.normalizeDownloadedLyrics("[00:01.00]纯音乐，请欣赏"))
    }

    @Test(expected = SerializationException::class)
    fun malformedDownloadedPayloadIsParseFailureRatherThanAbsentLyrics() {
        KuGou.normalizeDownloadedLyrics("<html>temporary gateway page</html>")
    }

    private fun candidate(id: Long, durationMs: Long) = SearchLyricsResponse.Candidate(
        id = id,
        productFrom = "",
        duration = durationMs,
        accesskey = "key",
    )

    @Test fun keywordSearchSkipsWrongDurationBeforeChoosingCandidate() {
        val candidates = listOf(candidate(1, 150_000), candidate(2, 200_000))

        assertEquals(listOf(2L), matchingKeywordCandidates(candidates, 200).map { it.id })
    }

    @Test fun keywordSearchAllowsToleranceBoundaryAndUnknownSongDuration() {
        val candidates = listOf(candidate(1, 192_000), candidate(2, 208_000), candidate(3, 208_001))

        assertEquals(listOf(1L, 2L), matchingKeywordCandidates(candidates, 200).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), matchingKeywordCandidates(candidates, -1).map { it.id })
    }
}
