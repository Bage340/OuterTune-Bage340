import com.zionhuang.kugou.KuGou
import com.zionhuang.kugou.KuGou.generateKeyword
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Test {
    @Test
    fun test() = runBlocking {
        assumeTrue("Live lyrics tests require OUTERTUNE_LIVE_LYRICS_TESTS=true", System.getenv("OUTERTUNE_LIVE_LYRICS_TESTS") == "true")
        val candidates = KuGou.getLyricsCandidate(
            generateKeyword("千年以后", "陈零九"),
            285
        )
        assertTrue(candidates != null)
        val downloadedLyrics = KuGou.getLyrics("点水", "杨丞琳", 259)
        assertTrue(!downloadedLyrics.getOrThrow().isNullOrBlank())
    }

    @Test
    fun searchAlanWalkerSong() = runBlocking {
        assumeTrue("Live lyrics tests require OUTERTUNE_LIVE_LYRICS_TESTS=true", System.getenv("OUTERTUNE_LIVE_LYRICS_TESTS") == "true")
        val songName = "Faded"
        val artistName = "Alan Walker"

        val keyword = generateKeyword(songName, artistName)
        val song = KuGou.searchSongs(keyword)

        assertTrue(song.data.info.isNotEmpty())

        val candidates = KuGou.getLyricsCandidate(
            keyword,
            song.data.info.first().duration
        )

        assertTrue(candidates != null)

        val downloadedLyrics = KuGou.getLyrics(songName, artistName, song.data.info.first().duration)
        assertTrue(!downloadedLyrics.getOrThrow().isNullOrBlank())
    }
}
