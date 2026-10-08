package com.dd3boh.outertune.ui.menu

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.LibraryTransferHost
import com.dd3boh.outertune.ui.dialog.LibraryTransferRequest
import com.dd3boh.outertune.ui.dialog.MovePlaylistDialog
import com.dd3boh.outertune.transfer.TrackSource
import com.dd3boh.outertune.transfer.TransferDocument
import com.dd3boh.outertune.transfer.TransferPlaylist
import com.dd3boh.outertune.transfer.TransferTrack
import com.dd3boh.outertune.utils.getDownloadState
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouTubePlaylistMenu(
    navController: NavController,
    playlist: PlaylistItem,
    songs: List<SongItem> = emptyList(),
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val queueBoard by playerConnection.queueBoard.collectAsState()
    val dbPlaylist by database.playlistByBrowseId(playlist.id).collectAsState(initial = null)
    val exportScope = rememberCoroutineScope()
    var exportRequest by remember { mutableStateOf<LibraryTransferRequest?>(null) }
    var exportLoading by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    LibraryTransferHost(exportRequest) { exportRequest = null }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }
    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }
    var showMovePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(songs) {
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it.id] })
        }
    }


    YouTubeListItem(
        item = playlist,
        trailingContent = {
            if (playlist.id != "LM" && !playlist.isEditable) {
                IconButton(
                    onClick = {
                        if (dbPlaylist?.playlist == null) {
                            database.transaction {
                                val playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    isEditable = false,
                                    thumbnailUrl = playlist.thumbnail,
                                    remoteSongCount = playlist.songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                ).toggleLike()

                                insert(playlistEntity)
                                coroutineScope.launch(Dispatchers.IO) {
                                    songs.ifEmpty {
                                        YouTube.playlist(playlist.id).completed()
                                            .getOrNull()?.songs.orEmpty()
                                    }.map { it.toMediaMetadata() }
                                        .onEach(::insert)
                                        .mapIndexed { index, song ->
                                            PlaylistSongMap(
                                                songId = song.id,
                                                playlistId = playlistEntity.id,
                                                position = index
                                            )
                                        }
                                        .forEach(::insert)
                                }
                            }
                        } else {
                            database.transaction {
                                update(dbPlaylist!!.playlist.toggleLike())
                            }
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(if (dbPlaylist?.playlist?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                        tint = if (dbPlaylist?.playlist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }
            }
        }
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        playlist.playEndpoint?.let {
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                playerConnection.playQueue(
                    ListQueue(
                        playlistId = playlist.playEndpoint!!.playlistId,
                        title = playlist.title,
                        items = songs.map { it.toMediaMetadata() },
                    )
                )

                onDismiss()
            }
        }

        playlist.shuffleEndpoint?.let { shuffleEndpoint ->
            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                playerConnection.playQueue(
                    ListQueue(
                        playlistId = playlist.playEndpoint!!.playlistId,
                        title = playlist.title,
                        items = songs.map { it.toMediaMetadata() },
                        startShuffled = true,
                    )
                )
                onDismiss()
            }
        }

        playlist.radioEndpoint?.let { radioEndpoint ->
            GridMenuItem(
                icon = Icons.Rounded.Radio,
                title = R.string.start_radio
            ) {
                playerConnection.playQueue(YouTubeQueue(radioEndpoint), isRadio = true)
                onDismiss()
            }
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next
        ) {
            coroutineScope.launch {
                songs.ifEmpty {
                    withContext(Dispatchers.IO) {
                        YouTube.playlist(playlist.id).completed().getOrNull()?.songs.orEmpty()
                    }
                }.let { songs ->
                    playerConnection.enqueueNext(songs.map { it.toMediaItem() })
                }
            }
            onDismiss()
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }

        if (songs.isNotEmpty()) {
            val cancelDownload = downloadState == Download.STATE_QUEUED || downloadState == Download.STATE_DOWNLOADING
            DownloadGridMenu(
                state = downloadState,
                onDownload = {
                    val _songs = songs.map { it.toMediaMetadata() }
                    downloadUtil.download(_songs)
                },
                onRemoveDownload = {
                    if (cancelDownload) {
                        downloadUtil.removeDownloads(songs.map { it.id }, cancelOnly = true)
                    } else {
                        showRemoveDownloadDialog = true
                    }
                }
            )
        }

        if (dbPlaylist != null) {
            GridMenuItem(
                icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                title = R.string.move_to_folder,
            ) {
                showMovePlaylistDialog = true
            }
        }

        GridMenuItem(
            icon = Icons.Rounded.Output,
            title = R.string.transfer_export_playlist,
        ) {
            if (!exportLoading) {
                exportLoading = true
                exportScope.launch {
                    try {
                        val completeSongs = withContext(Dispatchers.IO) {
                            YouTube.playlist(playlist.id).completed().getOrThrow().songs
                        }
                        exportRequest = LibraryTransferRequest.ExportDocument(
                            remotePlaylistTransferDocument(playlist, completeSongs),
                            playlist.title,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        exportError = failure.message ?: resources.getString(R.string.transfer_failed)
                    } finally {
                        exportLoading = false
                    }
                }
            }
        }

        GridMenuItem(
            icon = Icons.Rounded.Share,
            title = R.string.share
        ) {
            val intent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, playlist.shareLink)
            }
            context.startActivity(Intent.createChooser(intent, null))
            onDismiss()
        }

        GridMenuItem(
            icon = Icons.Rounded.PlaylistRemove,
            title = R.string.delete
        ) {
            showDeletePlaylistDialog = true
        }
    }

    if (showMovePlaylistDialog) {
        MovePlaylistDialog(
            onMove = { destination ->
                dbPlaylist?.let {
                    database.query { movePlaylistToFolder(it.id, destination) }
                }
            },
            onDismiss = { showMovePlaylistDialog = false },
        )
    }

    if (exportLoading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.transfer_in_progress)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text(stringResource(R.string.transfer_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                coroutineScope.launch {
                    songs.ifEmpty {
                        withContext(Dispatchers.IO) {
                            YouTube.playlist(playlist.id).completed().getOrNull()?.songs.orEmpty()
                        }
                    }.let { songs ->
                        val q = queueBoard.addQueue(
                            queueName, songs.map { it.toMediaMetadata() },
                            forceInsert = true, delta = false
                        )
                        q?.let {
                            queueBoard.setCurrQueue(it)
                        }
                    }
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = null,
            onPreAdd = {
                val allSongs = YouTube.playlist(playlist.id).completed().getOrThrow().songs
                    .map { it.toMediaMetadata() }
                database.withTransferTransaction {
                    allSongs.forEach(::insert)
                }
                allSongs.map { it.id }
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlist.title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        downloadUtil.removeDownloads(songs.map { it.id }, cancelOnly = false)
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlist.title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDismiss()
                        database.transaction {
                            deletePlaylistById(playlist.id)
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}

internal fun remotePlaylistTransferDocument(playlist: PlaylistItem, songs: List<SongItem>): TransferDocument =
    TransferDocument(
        library = emptyList(),
        playlists = listOf(
            TransferPlaylist(
                stableId = playlist.id,
                title = playlist.title,
                tracks = songs.map { song ->
                    TransferTrack(
                        source = TrackSource.YOUTUBE,
                        stableId = song.id,
                        title = song.title,
                        artists = song.artists.map { it.name },
                        album = song.album?.name,
                        durationSeconds = song.duration,
                    )
                },
            )
        ),
    )
