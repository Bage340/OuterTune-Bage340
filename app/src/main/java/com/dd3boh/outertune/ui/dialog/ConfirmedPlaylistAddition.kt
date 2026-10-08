package com.dd3boh.outertune.ui.dialog

internal suspend fun addConfirmedPlaylistSongs(
    songIds: List<String>,
    duplicatesToSkip: Set<String> = emptySet(),
    remoteAdd: suspend (List<String>) -> Unit,
    localAdd: suspend (List<String>) -> Unit,
) {
    val effectiveIds = songIds.filterNot { it in duplicatesToSkip }
    if (effectiveIds.isEmpty()) return
    remoteAdd(effectiveIds)
    localAdd(effectiveIds)
}
