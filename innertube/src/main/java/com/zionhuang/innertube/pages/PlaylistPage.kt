package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.MusicShelfRenderer
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.utils.parseTime

data class PlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val songsContinuation: String?,
    val continuation: String?,
    /** Whether all shelf rows were parsed; partial songs may still be displayed. */
    val snapshotComplete: Boolean = true,
) {
    companion object {
        fun fromBrowseResponse(response: BrowseResponse, playlistId: String): PlaylistPage {
            val columns = requireNotNull(response.contents?.twoColumnBrowseResultsRenderer) {
                "Missing playlist contents"
            }
            val base = columns.tabs.orEmpty().asSequence().flatMap {
                it?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty().asSequence()
            }.firstOrNull {
                it.musicResponsiveHeaderRenderer != null ||
                    it.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer != null
            }
            val header = requireNotNull(base?.musicResponsiveHeaderRenderer
                ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer) {
                "Missing playlist header"
            }
            val title = requireNotNull(header.title.runs?.firstOrNull()?.text).also {
                require(it.isNotBlank()) { "Missing playlist title" }
            }
            val section = columns.secondaryContents?.sectionListRenderer
            val shelf = requireNotNull(section?.contents?.firstNotNullOfOrNull { it.musicPlaylistShelfRenderer }) {
                "Missing playlist song shelf"
            }
            val parsed = parsePlaylistSongs(shelf.contents)
            val sectionContinuation = section.continuations?.getContinuation()
            val sectionMetadataComplete = section.continuations.isNullOrEmpty() || !sectionContinuation.isNullOrBlank()
            return PlaylistPage(
                playlist = PlaylistItem(
                    id = playlistId,
                    title = title,
                    author = header.straplineTextOne?.runs?.firstOrNull()?.let {
                        Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                    },
                    songCountText = header.secondSubtitle?.runs?.firstOrNull()?.text,
                    thumbnail = response.background?.musicThumbnailRenderer?.getThumbnailUrl(),
                    playEndpoint = header.buttons.getOrNull(1)?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                    shuffleEndpoint = header.buttons.getOrNull(2)?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = header.buttons.getOrNull(2)?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    isEditable = base?.musicEditablePlaylistDetailHeaderRenderer != null,
                ),
                songs = parsed.songs,
                songsContinuation = shelf.contents.getContinuation(),
                continuation = sectionContinuation,
                snapshotComplete = parsed.complete && sectionMetadataComplete,
            )
        }

        fun continuationFromBrowseResponse(response: BrowseResponse): PlaylistContinuationPage {
            response.continuationContents?.musicPlaylistShelfContinuation?.let {
                val parsed = parsePlaylistSongs(it.contents)
                val continuation = it.continuations?.getContinuation()
                val metadataComplete = it.continuations.isNullOrEmpty() || !continuation.isNullOrBlank()
                return PlaylistContinuationPage(
                    songs = parsed.songs,
                    continuation = continuation ?: it.contents.getContinuation(),
                    snapshotComplete = parsed.complete && metadataComplete,
                )
            }
            val items = requireNotNull(response.onResponseReceivedActions
                ?.firstNotNullOfOrNull { it.appendContinuationItemsAction }?.continuationItems) {
                "Missing playlist continuation contents"
            }
            val parsed = parsePlaylistSongs(items)
            return PlaylistContinuationPage(parsed.songs, items.getContinuation(), parsed.complete)
        }

        private data class ParsedSongs(val songs: List<SongItem>, val complete: Boolean)

        private fun parsePlaylistSongs(contents: List<MusicShelfRenderer.Content>): ParsedSongs {
            var complete = true
            val songs = contents.mapNotNull { row ->
                val renderer = row.musicResponsiveListItemRenderer
                if (renderer != null) {
                    fromMusicResponsiveListItemRenderer(renderer).also {
                        if (it == null) complete = false
                    }
                } else {
                    if (row.continuationItemRenderer == null || listOf(row).getContinuation().isNullOrBlank()) {
                        complete = false
                    }
                    null
                }
            }
            return ParsedSongs(songs, complete)
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            return SongItem(
                id = renderer.playlistItemData?.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.firstOrNull()?.text ?: return null,
                artists = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.oddElements()?.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                    )
                }.orEmpty(),
                album = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                    )
                },
                duration = renderer.fixedColumns?.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text?.parseTime(),
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                setVideoId = renderer.playlistItemData.playlistSetVideoId ?: return null
            )
        }
    }
}
