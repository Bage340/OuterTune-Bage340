package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())
    var continuation: String? = null
    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val isLoading = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            YouTube.playlist(playlistId)
                .onSuccess { playlistPage ->
                    playlist.value = playlistPage.playlist
                    playlistSongs.value = playlistPage.songs
                    continuation = playlistPage.songsContinuation
                }.onFailure {
                    reportException(it)
                }
            isLoading.value = false
        }
    }

    fun loadMoreSongs() {
        continuation?.let {
            viewModelScope.launch(Dispatchers.IO) {
                isLoading.value = true
                try {
                    getContinuation(it)
                } finally {
                    isLoading.value = false
                }
            }
        }
    }

    fun loadRemainingSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                while (continuation != null) {
                    if (!getContinuation(continuation!!)) break
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    suspend fun getContinuation(continuation: String): Boolean {
        val continuationPage = YouTube.playlistContinuation(continuation).getOrElse { e ->
            if (e is CancellationException) throw e
            reportException(e)
            return false
        }
        playlistSongs.value = playlistSongs.value + continuationPage.songs
        this.continuation = continuationPage.continuation
        return true
    }
}
