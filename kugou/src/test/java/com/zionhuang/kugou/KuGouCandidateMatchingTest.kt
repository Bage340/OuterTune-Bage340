package com.zionhuang.kugou

import com.zionhuang.kugou.models.SearchLyricsResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class KuGouCandidateMatchingTest {
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
