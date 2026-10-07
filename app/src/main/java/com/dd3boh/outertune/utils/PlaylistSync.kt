package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.PlaylistPage
import kotlinx.coroutines.CancellationException

internal suspend fun MusicDatabase.replaceSyncedPlaylist(playlistId: String, page: PlaylistPage): Boolean {
    if (!page.snapshotComplete || page.songsContinuation != null || page.continuation != null) return false
    val songs = page.songs
    return try {
        withTransferTransaction {
            // Empty remote reads cannot safely prove that a cached playlist was intentionally cleared.
            if (songs.isEmpty() && playlistUniqueSongCount(playlistId) > 0) return@withTransferTransaction false
            clearPlaylist(playlistId)
            val metadata = songs.map(SongItem::toMediaMetadata).onEach { insert(it) }
            metadata.forEachIndexed { position, song ->
                insert(PlaylistSongMap(songId = song.id, playlistId = playlistId, position = position, setVideoId = song.setVideoId))
            }
            true
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }
}
