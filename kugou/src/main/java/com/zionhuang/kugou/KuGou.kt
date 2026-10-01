package com.zionhuang.kugou

import com.zionhuang.kugou.models.DownloadLyricsResponse
import com.zionhuang.kugou.models.Keyword
import com.zionhuang.kugou.models.SearchLyricsResponse
import com.zionhuang.kugou.models.SearchSongResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.lang.Integer.min
import java.text.Normalizer
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.math.abs

@OptIn(ExperimentalSerializationApi::class)
private val client = HttpClient {
    expectSuccess = true

    install(ContentNegotiation) {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
        json(json)
        json(json, ContentType.Text.Html)
        json(json, ContentType.Text.Plain)
    }

    install(ContentEncoding) {
        gzip()
        deflate()
    }
}

private const val PAGE_SIZE = 8
private const val HEAD_CUT_LIMIT = 30

/** Decodes a standard Base64 string to its UTF-8 text (replaces Ktor's deprecated decodeBase64String). */
private fun String.decodeBase64ToString(): String =
    Base64.Default.decode(this).decodeToString()

/**
 * KuGou Lyrics Library
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object KuGou {
    var useTraditionalChinese: Boolean = false

    /**
     * Look up lyrics. Returns success with the raw text when a match is found, success with null when
     * the search succeeded but had no candidate (or the track is instrumental) — a definitive absence —
     * and a failure when the request itself failed. Non-2xx responses throw because [expectSuccess] is set.
     */
    suspend fun getLyrics(title: String, artist: String, duration: Int, album: String? = null): Result<String?> =
        runCatching {
            val keyword = generateKeyword(title, artist)
            val candidate = getLyricsCandidate(keyword, duration, album) ?: return@runCatching null
            normalizeDownloadedLyrics(downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64ToString())
        }

    suspend fun getAllPossibleLyricsOptions(
        title: String, artist: String, duration: Int, callback: (String) -> Unit
    ) {
        val keyword = generateKeyword(title, artist)
        matchingSongCandidates(searchSongs(keyword).data.info, title, artist, duration).forEach { song ->
            matchingKeywordCandidates(searchLyricsByHash(song.hash).candidates, duration, title, artist).forEach { candidate ->
                downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64ToString()
                    .let(::normalizeDownloadedLyrics)?.let(callback)
            }
        }
        matchingKeywordCandidates(searchLyricsByKeyword(keyword, duration).candidates, duration, title, artist).forEach { candidate ->
            downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64ToString()
                .let(::normalizeDownloadedLyrics)?.let(callback)
        }
    }

    suspend fun getLyricsCandidate(
        keyword: Keyword, duration: Int, album: String? = null,
    ): SearchLyricsResponse.Candidate? {
        matchingSongCandidates(searchSongs(keyword).data.info, keyword.title, keyword.artist, duration, album).forEach { song ->
            val candidate = matchingKeywordCandidates(searchLyricsByHash(song.hash).candidates, duration, keyword.title, keyword.artist).firstOrNull()
            if (candidate != null) return candidate
        }
        return matchingKeywordCandidates(searchLyricsByKeyword(keyword, duration).candidates, duration, keyword.title, keyword.artist).firstOrNull()
    }

    suspend fun searchSongs(keyword: Keyword) =
        client.get("https://mobileservice.kugou.com/api/v3/search/song") {
            parameter("version", 9108)
            parameter("plat", 0)
            parameter("pagesize", PAGE_SIZE)
            parameter("showtype", 0)
            url.encodedParameters.append(
                "keyword",
                "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchSongResponse>().also {
            check(it.status == 1 && it.errcode == 0) { "KuGou song search failed: ${it.errcode} ${it.error}" }
        }

    private suspend fun searchLyricsByKeyword(keyword: Keyword, duration: Int) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter(
                "duration", duration.takeIf { it != -1 }?.toLong()?.times(1000)
            ) // if duration == -1, we don't care duration
            url.encodedParameters.append(
                "keyword",
                "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchLyricsResponse>().also(::requireSuccessfulLyricsSearch)

    private suspend fun searchLyricsByHash(hash: String) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("hash", hash)
        }.body<SearchLyricsResponse>().also(::requireSuccessfulLyricsSearch)

    private suspend fun downloadLyrics(id: Long, accessKey: String) =
        client.get("https://lyrics.kugou.com/download") {
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
            parameter("client", "pc")
            parameter("ver", 1)
            parameter("id", id)
            parameter("accesskey", accessKey)
        }.body<DownloadLyricsResponse>()

    fun generateKeyword(title: String, artist: String) =
        Keyword(title.trim(), artist.trim())

    private fun requireSuccessfulLyricsSearch(response: SearchLyricsResponse) {
        check(response.status == 200 && response.errcode == 200) {
            "KuGou lyrics search failed: ${response.errcode} ${response.errmsg}"
        }
    }

    internal fun normalizeDownloadedLyrics(content: String): String? {
        if ("纯音乐，请欣赏" in content || "酷狗音乐  就是歌多" in content) return null
        return content.normalize().takeIf(String::isNotBlank)
            ?: throw SerializationException("KuGou returned no parseable LRC lines")
    }

    private fun String.normalize(): String =
        replace("&apos;", "'").lines().filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                // Remove useless information such as singer, writer, composer, guitar, etc.
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }
                val finalLines = filteredLines.dropLast(tailCutLine)

                return@let finalLines.joinToString("\n")
            }

    @Suppress("RegExpRedundantEscape")
    private val ACCEPTED_REGEX = "\\[\\d+:\\d{2}(?:[.:]\\d+)?].*".toRegex()
    private val BANNED_REGEX = ".+].+[:：].+".toRegex()

}

/** KuGou's keyword endpoint can return other recordings even when given a duration hint. */
internal fun matchingKeywordCandidates(
    candidates: List<SearchLyricsResponse.Candidate>,
    durationSeconds: Int,
    title: String? = null,
    artist: String? = null,
): List<SearchLyricsResponse.Candidate> {
    val expectedMs = durationSeconds.toLong() * 1000
    val toleranceMs = 8_000L
    return candidates.filter {
        (durationSeconds == -1 || it.duration in (expectedMs - toleranceMs)..(expectedMs + toleranceMs)) &&
            (title == null || sameTitle(it.song, title)) && (artist == null || sameArtist(it.singer, artist))
    }
}

internal fun matchingSongCandidates(
    songs: List<SearchSongResponse.Data.Info>,
    title: String,
    artist: String,
    durationSeconds: Int,
    album: String? = null,
): List<SearchSongResponse.Data.Info> = songs.filter {
    it.hash.isNotBlank() && sameTitle(it.songname, title) && sameArtist(it.singername, artist) &&
        (album.isNullOrBlank() || it.albumName.isNullOrBlank() || sameTitle(it.albumName, album)) &&
        (durationSeconds == -1 || abs(it.duration.toLong() - durationSeconds) <= 8)
}

private fun normalizedIdentity(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun sameTitle(candidate: String?, requested: String): Boolean =
    !candidate.isNullOrBlank() && normalizedIdentity(requested).isNotEmpty() &&
        normalizedIdentity(candidate) == normalizedIdentity(requested)

private fun sameArtist(candidate: String?, requested: String): Boolean {
    if (candidate.isNullOrBlank()) return false
    fun artists(value: String) = value.split(Regex("[,、;&/＋+]|\\s+&\\s+"))
        .map(::normalizedIdentity).filter(String::isNotEmpty).toSet()
    val expected = artists(requested)
    return expected.isNotEmpty() && artists(candidate) == expected
}
