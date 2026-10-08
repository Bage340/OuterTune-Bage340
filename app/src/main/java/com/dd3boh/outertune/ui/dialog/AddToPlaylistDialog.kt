package com.dd3boh.outertune.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.PlaylistSortTypeKey
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.PlaylistListItem
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddToPlaylistDialog(
    navController: NavController,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    songIds: List<String>?, // song ids to insert.
    onPreAdd: (suspend (Playlist) -> List<String>)? = null,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSortTypeKey, PlaylistSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSortDescendingKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)
    val requestedSongIds = songIds

    var playlists by remember {
        mutableStateOf(emptyList<Playlist>())
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showDuplicateDialog by remember {
        mutableStateOf(false)
    }
    var selectedPlaylist by remember {
        mutableStateOf<Playlist?>(null)
    }
    var songIds by remember {
        mutableStateOf<List<String>?>(songIds) // list is not saveable
    }
    var playlistIdsSongParticipation by remember {
        mutableStateOf<List<String>?>(null)
    }
    var duplicates by remember {
        mutableStateOf(emptyList<String>())
    }
    var isAdding by remember { mutableStateOf(false) }

    suspend fun addConfirmed(playlist: Playlist, ids: List<String>, skip: Set<String> = emptySet()) {
        withContext(Dispatchers.IO) {
            addConfirmedPlaylistSongs(ids, skip,
                remoteAdd = { effectiveIds ->
                    if (!playlist.playlist.isLocal) {
                        val remoteIds = effectiveIds.filter { id ->
                            val song = checkNotNull(database.song(id).first())
                            !song.song.isLocal
                        }
                        if (remoteIds.isNotEmpty()) {
                            val browseId = checkNotNull(playlist.playlist.browseId)
                            remoteIds.forEach { YouTube.addToPlaylist(browseId, it).getOrThrow() }
                        }
                    }
                },
                localAdd = { effectiveIds ->
                    database.withTransferTransaction {
                        val currentPlaylist = checkNotNull(database.playlist(playlist.id).first())
                        addSongToPlaylist(currentPlaylist, effectiveIds)
                    }
                },
            )
        }
        onDismiss()
    }

    fun launchAddition(action: suspend () -> Unit) {
        if (isAdding) return
        isAdding = true
        coroutineScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportException(error)
                Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_LONG).show()
            } finally {
                isAdding = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (syncMode == SyncMode.RO) {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 1).collect {
                playlists = it
            }
        } else {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 2).collect {
                playlists = it
            }
        }
    }

    LaunchedEffect(playlists) {
        coroutineScope.launch(Dispatchers.IO) {
            songIds?.let { s ->
                playlistIdsSongParticipation = database.playlistIdBySongs(s).first()
            }
        }
    }

    ListDialog(
        onDismiss = { if (!isAdding) onDismiss() }
    ) {
        item {
            ListItem(
                title = stringResource(R.string.create_playlist),
                thumbnailContent = {
                    Image(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                        modifier = Modifier.size(ListThumbnailSize)
                    )
                },
                modifier = Modifier.clickable(enabled = !isAdding) {
                    showCreatePlaylistDialog = true
                }
            )
        }

        item {
            InfoLabel(
                text = stringResource(R.string.playlist_add_local_to_synced_note),
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        item {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
                        PlaylistSortType.NAME -> R.string.sort_by_name
                        PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
            )
        }

        items(playlists) { playlist ->
            PlaylistListItem(
                playlist = playlist,
                subtitle = playlist.playlist.path,
                trailingContent = {
                    val inPlaylist =
                        playlistIdsSongParticipation != null && playlist.id in playlistIdsSongParticipation!!
                    // TODO: checkmark box for all songs in playlist for multiselect
                    val icon =
                        if (inPlaylist && songIds?.size == 1) {
                            Icons.Rounded.CheckBox
                        } else if (inPlaylist) {
                            Icons.Rounded.IndeterminateCheckBox
                        } else {
                            Icons.Rounded.CheckBoxOutlineBlank
                        }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                    )
                },
                modifier = Modifier.clickable(enabled = !isAdding && !showDuplicateDialog) {
                    selectedPlaylist = playlist
                    launchAddition {
                        val ids = withContext(Dispatchers.IO) {
                            val preparedIds = onPreAdd?.invoke(playlist)
                            val ids = requestedSongIds ?: checkNotNull(preparedIds)
                            duplicates = database.playlistDuplicates(playlist.id, ids)
                            ids
                        }
                        songIds = ids
                        if (duplicates.isNotEmpty()) {
                            showDuplicateDialog = true
                        } else {
                            addConfirmed(playlist, ids)
                        }
                    }
                }
            )
        }

        if (syncMode == SyncMode.RO) {
            item {
                TextButton(
                    onClick = {
                        navController.navigate("settings/account_sync")
                        onDismiss()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.playlist_missing_note),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = TextUnit(12F, TextUnitType.Sp),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning
    if (showDuplicateDialog) {
        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(
                    enabled = !isAdding,
                    onClick = {
                        launchAddition {
                            addConfirmed(checkNotNull(selectedPlaylist), checkNotNull(songIds), duplicates.toSet())
                            showDuplicateDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.skip_duplicates))
                }

                TextButton(
                    enabled = !isAdding,
                    onClick = {
                        launchAddition {
                            addConfirmed(checkNotNull(selectedPlaylist), checkNotNull(songIds))
                            showDuplicateDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.add_anyway))
                }

                TextButton(
                    enabled = !isAdding,
                    onClick = {
                        showDuplicateDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                if (!isAdding) showDuplicateDialog = false
            }
        ) {
            Text(
                text = if (duplicates.size == 1) {
                    stringResource(R.string.duplicates_description_single)
                } else {
                    stringResource(R.string.duplicates_description_multiple, duplicates.size)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}
