package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.Continuation
import com.zionhuang.innertube.models.GridRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.MusicShelfRenderer
import com.zionhuang.innertube.models.MusicTwoRowItemRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.utils.parseTime

data class LibraryPage(
    val items: List<YTItem>,
    val continuation: String?,
    /** Partial items can be displayed, but cannot authorize library reconciliation. */
    val snapshotComplete: Boolean = true,
) {
    companion object {
        fun fromBrowseResponse(response: BrowseResponse, tabIndex: Int = 0): LibraryPage {
            val section = requireNotNull(response.contents?.singleColumnBrowseResultsRenderer
                ?.tabs?.getOrNull(tabIndex)?.tabRenderer?.content?.sectionListRenderer) {
                "Missing library tab contents"
            }
            val parsed = fromSections(requireNotNull(section.contents) { "Missing library sections" }, section.continuations)
            return parsed.copy(snapshotComplete = parsed.snapshotComplete &&
                response.continuationContents == null && response.onResponseReceivedActions.isNullOrEmpty())
        }

        fun continuationFromBrowseResponse(response: BrowseResponse): LibraryContinuationPage {
            val contents = requireNotNull(response.continuationContents) { "Missing library continuation contents" }
            require((if (contents.gridContinuation != null) 1 else 0) +
                (if (contents.musicShelfContinuation != null) 1 else 0) == 1) {
                "Missing or ambiguous library continuation container"
            }
            val parsed = contents.gridContinuation?.let { parseGrid(it.items, it.continuations) }
                ?: parseShelf(requireNotNull(contents.musicShelfContinuation))
            return LibraryContinuationPage(
                items = parsed.items,
                continuation = parsed.continuation,
                snapshotComplete = parsed.snapshotComplete && contents.sectionListContinuation == null &&
                    contents.musicPlaylistShelfContinuation == null && response.onResponseReceivedActions.isNullOrEmpty(),
            )
        }

        fun recentActivityFromBrowseResponse(response: BrowseResponse): LibraryPage {
            val section = requireNotNull(response.continuationContents?.sectionListContinuation) {
                "Missing recent activity sections"
            }
            val page = fromSections(section.contents, section.continuations)
            require(page.snapshotComplete && page.continuation == null &&
                response.continuationContents.gridContinuation == null &&
                response.continuationContents.musicShelfContinuation == null &&
                response.continuationContents.musicPlaylistShelfContinuation == null &&
                response.onResponseReceivedActions.isNullOrEmpty()) { "Incomplete recent activity snapshot" }
            return page
        }

        private fun fromSections(
            sections: List<SectionListRenderer.Content>,
            continuations: List<Continuation>?,
        ): LibraryPage {
            val containers = sections.filter { it.gridRenderer != null || it.musicShelfRenderer != null }
            require(containers.size == 1) { "Missing or ambiguous library container" }
            val container = containers.single()
            require(container.gridRenderer == null || container.musicShelfRenderer == null) {
                "Ambiguous library container"
            }
            val parsed = container.gridRenderer?.let { parseGrid(it.items, it.continuations) }
                ?: parseShelf(requireNotNull(container.musicShelfRenderer))
            // Header/description sections contain no membership rows. Other unhandled sections are not authoritative.
            val sectionsComplete = sections.all {
                (it == container || it.musicDescriptionShelfRenderer != null ||
                    it.musicResponsiveHeaderRenderer != null || it.musicEditablePlaylistDetailHeaderRenderer != null) &&
                    it.musicCarouselShelfRenderer == null && it.musicCardShelfRenderer == null &&
                    it.musicPlaylistShelfRenderer == null && it.itemSectionRenderer == null
            }
            return parsed.copy(snapshotComplete = parsed.snapshotComplete && sectionsComplete && continuations.isNullOrEmpty())
        }

        private data class ParsedContinuation(val token: String?, val complete: Boolean)

        private fun parseContinuation(continuations: List<Continuation>?): ParsedContinuation {
            if (continuations.isNullOrEmpty()) return ParsedContinuation(null, true)
            val token = continuations.singleOrNull()?.nextContinuationData?.continuation
            return ParsedContinuation(token?.takeIf { it.isNotBlank() }, continuations.size == 1 && !token.isNullOrBlank())
        }

        private fun validItem(item: YTItem?): YTItem? = item?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }

        private fun parseGrid(rows: List<GridRenderer.Item>, continuations: List<Continuation>?): LibraryPage {
            var complete = true
            val items = rows.mapNotNull { row ->
                if (row.musicTwoRowItemRenderer != null) {
                    validItem(fromMusicTwoRowItemRenderer(row.musicTwoRowItemRenderer)).also {
                        if (it == null || row.musicNavigationButtonRenderer != null) complete = false
                    }
                } else {
                    if (row.musicNavigationButtonRenderer?.buttonText?.runs?.any { it.text.isNotBlank() } != true) {
                        complete = false
                    }
                    null
                }
            }
            val metadata = parseContinuation(continuations)
            return LibraryPage(items, metadata.token, complete && metadata.complete)
        }

        private fun parseShelf(shelf: MusicShelfRenderer): LibraryPage {
            val rows = requireNotNull(shelf.contents) { "Missing library shelf contents" }
            var complete = true
            val inlineTokens = mutableListOf<String>()
            val items = rows.mapNotNull { row ->
                if (row.musicResponsiveListItemRenderer != null) {
                    validItem(fromMusicResponsiveListItemRenderer(row.musicResponsiveListItemRenderer)).also {
                        if (it == null || row.continuationItemRenderer != null) complete = false
                    }
                } else {
                    val token = listOf(row).getContinuation()
                    if (row.continuationItemRenderer == null || token.isNullOrBlank()) {
                        complete = false
                    } else {
                        inlineTokens.add(token)
                    }
                    null
                }
            }
            val metadata = parseContinuation(shelf.continuations)
            val inlineToken = inlineTokens.singleOrNull()
            return LibraryPage(
                items = items,
                continuation = metadata.token ?: inlineToken,
                snapshotComplete = complete && metadata.complete && inlineTokens.size <= 1 &&
                    (metadata.token == null || inlineToken == null || metadata.token == inlineToken),
            )
        }

        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            return when {
                renderer.isAlbum -> AlbumItem(
                    browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint?.playlistId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = parseArtists(renderer.subtitle?.runs),
                    year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: return null,
                    explicit = renderer.subtitleBadges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )

                renderer.isPlaylist -> PlaylistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    author = null,
                    songCountText = renderer.subtitle?.runs?.lastOrNull()?.text,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl(),
                    playEndpoint = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    isEditable = renderer.menu?.menuRenderer?.items?.none {
                        // idk why we have the same menu even if on YTM webpage it shows different menus. I would rather
                        // check for a proper edit status or even a "delete playlist" item in the menu but... this is
                        // good enough for now...
                        // hax: You can't inLibrary a playlist you can edit, so check for that
                        it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "LIBRARY_SAVED"
                    } == true
                )

                renderer.isArtist -> ArtistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint = renderer.menu.menuRenderer.items.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                )

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            return when {
                renderer.isSong -> SongItem(
                        id = renderer.playlistItemData?.videoId ?: return null,
                        title = renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text
                            ?.runs?.firstOrNull()?.text ?: return null,
                        artists = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.oddElements()
                            ?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            } ?: emptyList(),
                        album = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                            ?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                        ?: return null
                                )
                            },
                        duration = renderer.fixedColumns?.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text?.parseTime(),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                            ?: return null,
                        explicit = renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                        endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint
                    )

                renderer.isArtist -> ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                        ?: return null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )

                else -> null
            }
        }

        private fun parseArtists(runs: List<Run>?): List<Artist> {
            val artists = mutableListOf<Artist>()

            if (runs != null) {
                for (run in runs) {
                    if (run.navigationEndpoint != null) {
                        artists.add(
                            Artist(
                                id = run.navigationEndpoint.browseEndpoint?.browseId!!,
                                name = run.text
                            )
                        )
                    }
                }
            }
            return artists
        }
    }
}
