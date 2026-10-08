package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.LYRIC_FETCH_TIMEOUT
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.lyrics.LyricsFetchRole
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.lyrics.LyricsResult
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel internal constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val searchLyrics: suspend (String, String, String, Int, (LyricsResult) -> Unit) -> Boolean,
    private val searchTimeoutMs: Long,
) : ViewModel() {
    @Inject constructor(lyricsHelper: LyricsHelper, database: MusicDatabase) : this(
        lyricsHelper, database,
        lyricsHelper::getAllLyrics, LYRIC_FETCH_TIMEOUT,
    )
    private var job: Job? = null
    private val searchLock = Any()
    private var searchGeneration = 0L
    private var refreshJob: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)
    val searchFailed = MutableStateFlow(false)

    fun search(mediaId: String, title: String, artist: String, duration: Int) = synchronized(searchLock) {
        val generation = ++searchGeneration
        job?.cancel()
        isLoading.value = true
        results.value = emptyList()
        searchFailed.value = false
        job = viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            try {
                failed = withTimeoutOrNull(searchTimeoutMs) {
                    searchLyrics(mediaId, title, artist, duration) { result ->
                        synchronized(searchLock) {
                            if (generation == searchGeneration) results.update { it + result }
                        }
                    }
                } != true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                reportException(e)
            } finally {
                synchronized(searchLock) {
                    if (generation == searchGeneration) {
                        searchFailed.value = failed
                        isLoading.value = false
                    }
                }
            }
        }
    }

    fun cancelSearch() = synchronized(searchLock) {
        searchGeneration++
        job?.cancel()
        job = null
        isLoading.value = false
    }

    fun refetchLyrics(mediaMetadata: MediaMetadata) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(LYRIC_FETCH_TIMEOUT) {
                    // Keep the existing row until its replacement is ready so cancellation does not discard usable lyrics.
                    lyricsHelper.fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL, forceRefresh = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }
}
