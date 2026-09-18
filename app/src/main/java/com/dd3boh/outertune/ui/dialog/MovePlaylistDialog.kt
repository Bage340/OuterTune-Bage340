package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.utils.PlaylistFolders

@Composable
fun MovePlaylistDialog(
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val folders by database.playlistFolders().collectAsState(initial = emptyList())
    val destinations = listOf(PlaylistFolders.ROOT) + folders.map { it.path }

    ListDialog(onDismiss = onDismiss) {
        items(destinations, key = { it }) { path ->
            ListItem(
                title = if (path == PlaylistFolders.ROOT) {
                    stringResource(R.string.root_folder)
                } else {
                    PlaylistFolders.name(path)
                },
                subtitle = path,
                thumbnailContent = {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(ListThumbnailSize).padding(8.dp),
                    )
                },
                modifier = Modifier.clickable {
                    onMove(path)
                    onDismiss()
                },
            )
        }
    }
}
