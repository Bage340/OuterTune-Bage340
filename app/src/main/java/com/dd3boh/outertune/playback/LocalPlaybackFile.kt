package com.dd3boh.outertune.playback

import android.content.ContentResolver
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.dd3boh.outertune.models.MediaMetadata
import java.io.File
import java.io.IOException
import java.net.URI

private val localSongIdPattern = Regex("LS[A-Za-z]{8}")

internal fun findLocalPlaybackUri(
    contentResolver: ContentResolver,
    databaseSong: MediaMetadata?,
    queueSong: MediaMetadata?,
): Uri? = localPlaybackCandidates(databaseSong, queueSong).firstNotNullOfOrNull { reference ->
    if (reference.startsWith("content://")) {
        val uri = Uri.parse(reference)
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    } else {
        readableLocalFile(reference)?.let(Uri::fromFile)
    }
}

internal fun resolveLocalPlaybackDataSpec(
    dataSpec: DataSpec,
    contentResolver: ContentResolver,
    databaseSong: MediaMetadata?,
    queueSong: MediaMetadata?,
): DataSpec? = findLocalPlaybackUri(contentResolver, databaseSong, queueSong)?.let(dataSpec::withUri)

internal fun isLocalPlayback(mediaId: String, databaseSong: MediaMetadata?, queueSong: MediaMetadata?): Boolean =
    databaseSong?.isLocal == true || queueSong?.isLocal == true || localSongIdPattern.matches(mediaId)

internal fun findLocalPlaybackFile(databaseSong: MediaMetadata?, queueSong: MediaMetadata?): File? =
    localPlaybackCandidates(databaseSong, queueSong).firstNotNullOfOrNull(::readableLocalFile)

private fun localPlaybackCandidates(databaseSong: MediaMetadata?, queueSong: MediaMetadata?): List<String> {
    // Both local scanners store the audio file path as song artwork. Older scans
    // cleared localPath when disabling a song, but left this exact path intact.
    val candidates = listOf(
        databaseSong?.localPath,
        queueSong?.localPath,
        databaseSong?.takeIf { it.isLocal }?.thumbnailUrl,
        queueSong?.takeIf { it.isLocal }?.thumbnailUrl,
    )
    return candidates.filterNotNull().distinct()
}

private fun readableLocalFile(reference: String): File? {
    val file = if (reference.startsWith("file:")) {
        try {
            val uri = URI.create(reference)
            if (uri.authority == "localhost" && uri.rawQuery == null && uri.rawFragment == null) {
                File(uri.path)
            } else {
                File(uri)
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
    } else {
        File(reference)
    }
    return file.takeIf { it.isAbsolute && it.isFile && it.canRead() }
}
