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
class LyricsMenuViewModel @Inject constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
) : ViewModel() {
    private var job: Job? = null
    private var refreshJob: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    fun search(mediaId: String, title: String, artist: String, duration: Int) {
        isLoading.value = true
        results.value = emptyList()
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(LYRIC_FETCH_TIMEOUT) {
                    lyricsHelper.getAllLyrics(mediaId, title, artist, duration) { result ->
                        results.update {
                            it + result
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                isLoading.value = false
            }
        }
    }

    fun cancelSearch() {
        job?.cancel()
        job = null
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
