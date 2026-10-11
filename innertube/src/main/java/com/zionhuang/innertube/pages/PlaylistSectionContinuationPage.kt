package com.zionhuang.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Section pages are unrelated recommendations only after their raw renderer shape is verified. */
data class PlaylistSectionContinuationPage(val continuation: String?) {
    companion object {
        fun fromResponse(response: JsonObject): PlaylistSectionContinuationPage {
            require(response["contents"].isAbsent() && response["onResponseReceivedActions"].isAbsentOrEmptyArray()) {
                "Unconsumed playlist section continuation"
            }
            val contents = requireNotNull(response["continuationContents"] as? JsonObject) {
                "Missing playlist section continuation"
            }
            require(contents.keys == setOf("sectionListContinuation")) { "Unsupported playlist section continuation" }
            val section = requireNotNull(contents["sectionListContinuation"] as? JsonObject)
            require(section.keys.all { it in setOf("contents", "continuations", "trackingParams", "targetId") }) {
                "Unsupported playlist section metadata"
            }
            val rows = requireNotNull(section["contents"] as? JsonArray)
            require(rows.isNotEmpty() && rows.all(::isPlaylistCarousel)) { "Unconsumed playlist section continuation" }
            return PlaylistSectionContinuationPage(sectionToken(section["continuations"]))
        }

        fun initialSectionComplete(response: JsonObject): Boolean {
            if (!response["continuationContents"].isAbsent() || !response["onResponseReceivedActions"].isAbsentOrEmptyArray()) return false
            val section = response.objectAt("contents")?.objectAt("twoColumnBrowseResultsRenderer")
                ?.objectAt("secondaryContents")?.objectAt("sectionListRenderer") ?: return false
            if (section.keys.any { it !in setOf("contents", "continuations", "trackingParams", "targetId") }) return false
            val rows = section["contents"] as? JsonArray ?: return false
            return rows.count { (it as? JsonObject)?.containsKey("musicPlaylistShelfRenderer") == true } == 1 &&
                rows.all { element ->
                    val row = element as? JsonObject ?: return@all false
                    when {
                        row.keys == setOf("musicPlaylistShelfRenderer") ->
                            isInitialSongShelf(row.objectAt("musicPlaylistShelfRenderer"))
                        else -> isPlaylistCarousel(row)
                    }
                } && runCatching { sectionToken(section["continuations"]) }.isSuccess
        }

        fun songRowsComplete(element: JsonElement?): Boolean = (element as? JsonArray)?.all {
            val row = it as? JsonObject ?: return@all false
            when (row.keys) {
                setOf("musicResponsiveListItemRenderer") -> row.objectAt("musicResponsiveListItemRenderer") != null
                setOf("continuationItemRenderer") -> isInlineContinuation(row.objectAt("continuationItemRenderer"))
                else -> false
            }
        } == true

        private fun isInitialSongShelf(shelf: JsonObject?): Boolean {
            if (shelf == null || shelf.keys.any { it !in setOf(
                    "playlistId", "header", "contents", "collapsedItemCount", "trackingParams", "contentsMultiSelectable", "targetId", "continuations",
                ) }) return false
            // This initial renderer's paging is represented by inline rows, not a shelf continuation field.
            return shelf["continuations"].isAbsentOrEmptyArray() && songRowsComplete(shelf["contents"])
        }

        private fun isInlineContinuation(renderer: JsonObject?): Boolean {
            if (renderer == null || renderer.keys.any { it !in setOf("trigger", "continuationEndpoint", "trackingParams") }) return false
            val endpoint = renderer.objectAt("continuationEndpoint") ?: return false
            if (endpoint.keys.any { it !in setOf("clickTrackingParams", "commandMetadata", "continuationCommand") }) return false
            val command = endpoint.objectAt("continuationCommand") ?: return false
            return command.keys.all { it in setOf("token", "request") } && !command["token"].stringValue().isNullOrBlank()
        }

        fun songContinuationComplete(response: JsonObject): Boolean {
            if (!response["contents"].isAbsent()) return false
            val rawActions = response["onResponseReceivedActions"]
            if (!rawActions.isAbsent() && rawActions !is JsonArray) return false
            val actions = response["onResponseReceivedActions"] as? JsonArray
            if (actions.orEmpty().any {
                    val action = it as? JsonObject
                    action == null || action.isEmpty() || action.keys.any { key -> key !in setOf("clickTrackingParams", "appendContinuationItemsAction") }
                }) return false
            val appendActions = actions.orEmpty().mapNotNull { (it as? JsonObject)?.objectAt("appendContinuationItemsAction") }
            val contents = response["continuationContents"] as? JsonObject
            if (contents != null) {
                if (contents.keys != setOf("musicPlaylistShelfContinuation") || appendActions.isNotEmpty()) return false
                val shelf = contents.objectAt("musicPlaylistShelfContinuation") ?: return false
                if (shelf.keys.any { it !in setOf("contents", "continuations", "trackingParams", "targetId", "playlistId") }) return false
                return songRowsComplete(shelf["contents"]) && runCatching { sectionToken(shelf["continuations"]) }.isSuccess
            }
            if (appendActions.size != 1) return false
            val append = appendActions.single()
            return append.keys.all { it in setOf("continuationItems", "targetId", "trackingParams") } && songRowsComplete(append["continuationItems"])
        }

        private fun sectionToken(element: JsonElement?): String? {
            if (element == null) return null
            val entries = requireNotNull(element as? JsonArray) { "Invalid playlist section continuation metadata" }
            if (entries.isEmpty()) return null
            require(entries.size == 1) { "Ambiguous playlist section continuation" }
            val entry = requireNotNull(entries.single() as? JsonObject)
            require(entry.keys == setOf("nextContinuationData")) { "Invalid playlist section continuation metadata" }
            val data = requireNotNull(entry.objectAt("nextContinuationData"))
            require(data.keys.all { it in setOf("continuation", "clickTrackingParams", "timeoutMs") }) {
                "Invalid playlist section continuation metadata"
            }
            return requireNotNull(data["continuation"].stringValue()) {
                "Missing playlist section continuation token"
            }.also { require(it.isNotBlank()) { "Invalid playlist section continuation token" } }
        }

        private fun isPlaylistCarousel(element: JsonElement): Boolean {
            val row = element as? JsonObject ?: return false
            if (row.keys != setOf("musicCarouselShelfRenderer")) return false
            val carousel = row.objectAt("musicCarouselShelfRenderer") ?: return false
            if (carousel.keys.any { it !in setOf("header", "contents", "trackingParams", "itemSize", "numItemsPerColumn") }) return false
            val cards = carousel["contents"] as? JsonArray ?: return false
            return cards.isNotEmpty() && cards.all(::isPlaylistCard)
        }

        private fun isPlaylistCard(element: JsonElement): Boolean {
            val row = element as? JsonObject ?: return false
            if (row.keys != setOf("musicTwoRowItemRenderer")) return false
            val card = row.objectAt("musicTwoRowItemRenderer") ?: return false
            if (card.keys.any { it !in setOf(
                    "thumbnailRenderer", "aspectRatio", "title", "subtitle", "subtitleBadges", "navigationEndpoint",
                    "trackingParams", "menu", "thumbnailOverlay", "thumbnailCornerOverlay",
                ) }) return false
            val endpoint = card.objectAt("navigationEndpoint") ?: return false
            if (endpoint.keys.any { it !in setOf("clickTrackingParams", "browseEndpoint", "commandMetadata") }) return false
            val browse = endpoint.objectAt("browseEndpoint") ?: return false
            val id = browse["browseId"].stringValue()
            val pageType = browse.objectAt("browseEndpointContextSupportedConfigs")
                ?.objectAt("browseEndpointContextMusicConfig")?.get("pageType").stringValue()
            return !id.isNullOrBlank() && pageType == "MUSIC_PAGE_TYPE_PLAYLIST"
        }

        private fun JsonObject.objectAt(key: String) = this[key] as? JsonObject
        private fun JsonElement?.stringValue() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonElement?.isAbsent() = this == null || this == JsonNull
        private fun JsonElement?.isAbsentOrEmptyArray() = isAbsent() || (this is JsonArray && isEmpty())
    }
}
