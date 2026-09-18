package com.dd3boh.outertune.ui.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.MoveFolderDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.utils.PlaylistFolders

@Composable
fun PlaylistFolderMenu(
    path: String,
    onRename: (String) -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    Text(text = path)
    GridMenu {
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.DriveFileMove,
            title = R.string.move_to_folder,
            onClick = { showMoveDialog = true },
        )
        GridMenuItem(
            icon = Icons.Rounded.Edit,
            title = R.string.rename_folder,
            onClick = { showRenameDialog = true },
        )
        GridMenuItem(
            icon = Icons.Rounded.Delete,
            title = R.string.delete_folder,
            onClick = { showDeleteDialog = true },
        )
    }

    if (showMoveDialog) {
        MoveFolderDialog(
            source = path,
            onMove = {
                onMove(it)
                onDismiss()
            },
            onDismiss = { showMoveDialog = false },
        )
    }

    if (showRenameDialog) {
        TextFieldDialog(
            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            title = { Text(stringResource(R.string.rename_folder)) },
            initialTextFieldValue = TextFieldValue(PlaylistFolders.name(path)),
            isInputValid = PlaylistFolders::validName,
            onDone = {
                onRename(it)
                onDismiss()
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        DefaultDialog(
            title = { Text(stringResource(R.string.delete_folder)) },
            onDismiss = { showDeleteDialog = false },
            buttons = {
                androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                androidx.compose.material3.TextButton(onClick = {
                    onDelete()
                    onDismiss()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Text(stringResource(R.string.delete_folder_confirm, PlaylistFolders.name(path)))
        }
    }
}
