package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistFolderEntity
import com.dd3boh.outertune.utils.PlaylistFolders
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.extensions.reversed
import com.zionhuang.innertube.models.PlaylistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/*
 * Logic related to playlists entities and their mapping
 */

@Dao
interface PlaylistsDao {

    @Query("SELECT * FROM playlist_folder ORDER BY path COLLATE NOCASE")
    fun playlistFolders(): Flow<List<PlaylistFolderEntity>>

    @Query("SELECT path FROM playlist_folder")
    fun playlistFolderPaths(): List<String>

    @Query("SELECT * FROM playlist")
    fun playlistEntities(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(folder: PlaylistFolderEntity)

    @Query("DELETE FROM playlist_folder WHERE path = :path")
    fun deletePlaylistFolderRow(path: String)

    @Query("UPDATE playlist SET path = :path WHERE id = :playlistId")
    fun setPlaylistPath(playlistId: String, path: String): Int

    @Query("UPDATE playlist SET name = :name WHERE id = :playlistId")
    fun renamePlaylist(playlistId: String, name: String)

    @Transaction
    fun createPlaylistFolder(parent: String, name: String): Boolean {
        if (!PlaylistFolders.validName(name)) return false
        val base = PlaylistFolders.canonical(parent)
        val paths = playlistFolderPaths()
        if (base != PlaylistFolders.ROOT && PlaylistFolders.resolve(base, paths) == null) return false
        val path = PlaylistFolders.child(base, name)
        if (PlaylistFolders.resolve(path, paths) != null) return false
        insert(PlaylistFolderEntity(path))
        return true
    }

    @Transaction
    fun movePlaylistToFolder(playlistId: String, destination: String): Boolean {
        val path = PlaylistFolders.canonical(destination)
        val resolvedPath = if (path == PlaylistFolders.ROOT) path
        else PlaylistFolders.resolve(path, playlistFolderPaths()) ?: return false
        return setPlaylistPath(playlistId, resolvedPath) == 1
    }

    fun renamePlaylistFolder(path: String, name: String): Boolean {
        val source = PlaylistFolders.canonical(path)
        return relocatePlaylistFolder(source, PlaylistFolders.parent(source), name)
    }

    fun movePlaylistFolder(path: String, destinationParent: String): Boolean =
        relocatePlaylistFolder(path, destinationParent, PlaylistFolders.name(path))

    @Transaction
    fun relocatePlaylistFolder(path: String, destinationParent: String, newName: String): Boolean {
        val source = PlaylistFolders.canonical(path)
        val plan = PlaylistFolders.relocationPlan(
            source = source,
            destinationParent = destinationParent,
            paths = playlistFolderPaths(),
            newName = newName,
        ) ?: return false
        if (plan.isEmpty()) return true

        plan.values.forEach { insert(PlaylistFolderEntity(it)) }
        val target = plan.getValue(source)
        playlistEntities().filter { PlaylistFolders.contains(source, it.path) }.forEach {
            setPlaylistPath(it.id, PlaylistFolders.relocate(it.path, source, target))
        }
        plan.keys.sortedByDescending { it.length }.forEach(::deletePlaylistFolderRow)
        return true
    }

    /** Remove organization only; every playlist in the subtree survives in the parent. */
    @Transaction
    fun deletePlaylistFolder(path: String): Boolean {
        val source = PlaylistFolders.resolve(path, playlistFolderPaths()) ?: return false
        if (source == PlaylistFolders.ROOT) return false
        playlistEntities().filter { PlaylistFolders.contains(source, it.path) }.forEach {
            setPlaylistPath(it.id, PlaylistFolders.pathAfterDelete(it.path, source))
        }
        playlistFolderPaths().filter { PlaylistFolders.contains(source, it) }.forEach(::deletePlaylistFolderRow)
        return true
    }

    // region Gets
    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE p.id = :playlistId
        GROUP BY p.id
    """)
    fun playlist(playlistId: String): Flow<Playlist?>

    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE p.browseId = :browseId
        GROUP BY p.id
    """)
    fun playlistByBrowseId(browseId: String): Flow<Playlist?>

    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE name LIKE '%' || :query || '%'
            AND s.inLibrary IS NOT NULL
        GROUP BY p.id
        LIMIT :previewSize
    """)
    fun searchPlaylists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Query("SELECT songId from playlist_song_map WHERE playlistId = :playlistId AND songId IN (:songIds)")
    fun playlistDuplicates(playlistId: String, songIds: List<String>,): List<String>

    @Query("SELECT * FROM playlist_song_map WHERE songId = :songId")
    fun songMapsToPlaylist(songId: String): List<PlaylistSongMap>

    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId AND position >= :from ORDER BY position")
    fun songMapsToPlaylist(playlistId: String, from: Int): List<PlaylistSongMap>

    @Query("""
        SELECT DISTINCT playlistId
        FROM playlist_song_map
        WHERE songId IN (:songs)
    """)
    fun playlistIdBySongs(songs: List<String>): Flow<List<String>>

    @Transaction
    @RawQuery(observedEntities = [PlaylistEntity::class])
    fun _getPlaylists(query: SupportSQLiteQuery): Flow<List<Playlist>>

    // TODO: do i even want an enum for this
    /**
     * Variant
     *
     * 0 -> Use this one (WHERE p.bookmarkedAt IS NOT NULL OR p.isLocal = 1)
     * 1 -> local playlists (WHERE p.isLocal AND p.bookmarkedAt IS NOT NULL)
     * 2 -> editable playlists (WHERE p.isEditable AND p.bookmarkedAt IS NOT NULL)
     */
    fun playlists(filter: PlaylistFilter, sortType: PlaylistSortType, descending: Boolean, variant: Int = 0, localSongsOnly: Boolean = false): Flow<List<Playlist>> {
        val orderBy = when (sortType) {
            PlaylistSortType.CREATE_DATE -> "p.rowId ASC"
            PlaylistSortType.NAME -> "p.name COLLATE NOCASE ASC"
            PlaylistSortType.SONG_COUNT -> "songCount ASC"
        }

        val having = when (filter) {
            PlaylistFilter.DOWNLOADED -> "HAVING SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) > 0"
            else -> ""
        }.let { base ->
            if (!localSongsOnly) base
            else if (base.isEmpty()) "HAVING SUM(CASE WHEN s.isLocal = 0 THEN 1 ELSE 0 END) = 0"
            else "$base AND SUM(CASE WHEN s.isLocal = 0 THEN 1 ELSE 0 END) = 0"
        }

        val where = when (variant) {
            1 -> "WHERE p.isLocal AND p.bookmarkedAt IS NOT NULL"
            2 -> "WHERE p.isEditable AND p.bookmarkedAt IS NOT NULL"
            else -> "WHERE p.bookmarkedAt IS NOT NULL OR p.isLocal = 1"
        }

        val query = SimpleSQLiteQuery("""
            SELECT 
                p.*, 
                COUNT(psm.playlistId) AS songCount,
                SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM playlist p
                LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
                LEFT JOIN song s ON psm.songId = s.id
            $where
            GROUP BY p.id
            $having
            ORDER BY $orderBy
        """)

        return _getPlaylists(query).map{ it.reversed(descending) }
    }

    fun playlistInLibraryAsc() = playlists(PlaylistFilter.LIBRARY, PlaylistSortType.CREATE_DATE, false)
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: PlaylistSongMap)
    // endregion

    // region Updates
    @Update
    fun update(playlist: PlaylistEntity)

    @Update
    fun update(map: PlaylistSongMap)

    fun update(playlistEntity: PlaylistEntity, playlistItem: PlaylistItem) {
        updatePlaylistMetadata(
            id = playlistEntity.id,
            name = playlistItem.title,
            browseId = playlistItem.id,
            isEditable = playlistItem.isEditable,
            thumbnailUrl = playlistItem.thumbnail,
            remoteSongCount = playlistItem.songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
            playEndpointParams = playlistItem.playEndpoint?.params,
            shuffleEndpointParams = playlistItem.shuffleEndpoint?.params,
            radioEndpointParams = playlistItem.radioEndpoint?.params
        )
    }

    @Query("""UPDATE playlist SET name = :name, browseId = :browseId,
        isEditable = :isEditable, thumbnailUrl = :thumbnailUrl, remoteSongCount = :remoteSongCount,
        playEndpointParams = :playEndpointParams, shuffleEndpointParams = :shuffleEndpointParams,
        radioEndpointParams = :radioEndpointParams WHERE id = :id""")
    fun updatePlaylistMetadata(id: String, name: String, browseId: String?, isEditable: Boolean,
        thumbnailUrl: String?, remoteSongCount: Int?, playEndpointParams: String?,
        shuffleEndpointParams: String?, radioEndpointParams: String?)

    @Transaction
    fun addSongToPlaylist(playlist: Playlist, songIds: List<String>) {
        var position = playlist.songCount
        songIds.forEach { id ->
            insert(
                PlaylistSongMap(
                    songId = id,
                    playlistId = playlist.id,
                    position = position++
                )
            )
        }
    }

    @Transaction
    @Query("UPDATE playlist SET isLocal = 1 WHERE id = :playlistId")
    fun playlistDesync(playlistId: String)

    @Transaction
    @Query(
        """
        UPDATE playlist_song_map SET position = 
            CASE 
                WHEN position < :fromPosition THEN position + 1
                WHEN position > :fromPosition THEN position - 1
                ELSE :toPosition
            END 
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition, :toPosition) AND MAX(:fromPosition, :toPosition)
    """
    )
    fun move(playlistId: String, fromPosition: Int, toPosition: Int)
    // endregion

    // region Deletes
    @Delete
    fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE browseId = :browseId")
    fun deletePlaylistById(browseId: String)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId")
    fun clearPlaylist(playlistId: String)

    @Delete
    fun delete(playlistSongMap: PlaylistSongMap)
    // endregion
}
