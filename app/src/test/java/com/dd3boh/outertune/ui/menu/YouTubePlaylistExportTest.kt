package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.transfer.TrackSource
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistExportTest {
    @Test
    fun remotePlaylistDocumentPreservesOrderDuplicatesAndMetadata() {
        val playlist = PlaylistItem("PLexample", "Remote mix", null, null, null, null, null, null)
        val song = SongItem(
            id = "AbCdEf12345",
            title = "First song",
            artists = listOf(Artist("One", null), Artist("Two", null)),
            album = Album("Album", "MPalbum"),
            duration = 143,
            thumbnail = "https://example.com/art.jpg",
        )

        val document = remotePlaylistTransferDocument(playlist, listOf(song, song.copy(title = "Reprise")))

        assertTrue(document.library.isEmpty())
        assertEquals("PLexample", document.playlists.single().stableId)
        assertEquals("Remote mix", document.playlists.single().title)
        assertEquals(listOf("First song", "Reprise"), document.playlists.single().tracks.map { it.title })
        assertEquals(TrackSource.YOUTUBE, document.playlists.single().tracks[0].source)
        assertEquals("AbCdEf12345", document.playlists.single().tracks[0].stableId)
        assertEquals(listOf("One", "Two"), document.playlists.single().tracks[0].artists)
        assertEquals("Album", document.playlists.single().tracks[0].album)
        assertEquals(143, document.playlists.single().tracks[0].durationSeconds)
    }
}
