package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
    /** Whether all shelf rows were parsed; partial songs may still be displayed. */
    val snapshotComplete: Boolean = true,
)
