package com.dd3boh.outertune.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Migration21To22Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        instrumentation.targetContext.getDatabasePath(TEST_DATABASE),
        AndroidSQLiteDriver(),
        InternalDatabase::class,
    )

    @Test
    fun migrationPreservesLocalAndRemotePlaylistsMetadataAndSongMappings() {
        helper.createDatabase(21).use { database ->
            database.execSQL(
                """INSERT INTO song (id, title, duration, liked, likedDate, inLibrary, dateDownload, isLocal, localPath)
                    VALUES ('song-1', 'Song', 123, 1, 111, 222, 333, 1, '/music/song.mp3')"""
            )
            database.execSQL(
                """INSERT INTO format (id, itag, mimeType, codecs, bitrate, contentLength)
                    VALUES ('song-1', -1, 'audio/mpeg', 'mp3', 320000, 1234567)"""
            )
            database.execSQL(
                """INSERT INTO playlist (
                    id, name, browseId, isEditable, bookmarkedAt, thumbnailUrl,
                    remoteSongCount, playEndpointParams, shuffleEndpointParams,
                    radioEndpointParams, isLocal
                ) VALUES (
                    'local', 'Local playlist', NULL, 1, 111, 'local-thumb',
                    NULL, NULL, NULL, NULL, 1
                )""".trimIndent()
            )
            database.execSQL(
                """INSERT INTO playlist (
                    id, name, browseId, isEditable, bookmarkedAt, thumbnailUrl,
                    remoteSongCount, playEndpointParams, shuffleEndpointParams,
                    radioEndpointParams, isLocal
                ) VALUES (
                    'remote', 'Remote playlist', 'VL_remote', 0, 222, 'remote-thumb',
                    42, 'play-params', 'shuffle-params', 'radio-params', 0
                )""".trimIndent()
            )
            database.execSQL(
                """INSERT INTO playlist_song_map
                    (playlistId, songId, position, setVideoId)
                    VALUES ('remote', 'song-1', 7, 'set-video')""".trimIndent()
            )
        }

        helper.runMigrationsAndValidate(22, listOf(MIGRATION_21_22)).use { database ->
            database.prepare(
                """SELECT id, name, browseId, isEditable, bookmarkedAt, thumbnailUrl,
                    remoteSongCount, playEndpointParams, shuffleEndpointParams,
                    radioEndpointParams, isLocal, path
                    FROM playlist ORDER BY id""".trimIndent()
            ).use { statement ->
                assertEquals(true, statement.step())
                assertEquals("local", statement.getText(0))
                assertEquals("Local playlist", statement.getText(1))
                assertEquals(true, statement.isNull(2))
                assertEquals(1L, statement.getLong(3))
                assertEquals(111L, statement.getLong(4))
                assertEquals("local-thumb", statement.getText(5))
                assertEquals(true, statement.isNull(6))
                assertEquals(true, statement.isNull(7))
                assertEquals(true, statement.isNull(8))
                assertEquals(true, statement.isNull(9))
                assertEquals(1L, statement.getLong(10))
                assertEquals("/", statement.getText(11))

                assertEquals(true, statement.step())
                assertEquals("remote", statement.getText(0))
                assertEquals("Remote playlist", statement.getText(1))
                assertEquals("VL_remote", statement.getText(2))
                assertEquals(0L, statement.getLong(3))
                assertEquals(222L, statement.getLong(4))
                assertEquals("remote-thumb", statement.getText(5))
                assertEquals(42L, statement.getLong(6))
                assertEquals("play-params", statement.getText(7))
                assertEquals("shuffle-params", statement.getText(8))
                assertEquals("radio-params", statement.getText(9))
                assertEquals(0L, statement.getLong(10))
                assertEquals("/", statement.getText(11))
                assertFalse(statement.step())
            }

            database.prepare(
                "SELECT playlistId, songId, position, setVideoId FROM playlist_song_map"
            ).use { statement ->
                assertEquals(true, statement.step())
                assertEquals("remote", statement.getText(0))
                assertEquals("song-1", statement.getText(1))
                assertEquals(7L, statement.getLong(2))
                assertEquals("set-video", statement.getText(3))
                assertFalse(statement.step())
            }

            database.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'playlist_folder'"
            ).use { statement ->
                assertEquals(true, statement.step())
                assertEquals("playlist_folder", statement.getText(0))
                assertFalse(statement.step())
            }
            database.prepare("PRAGMA foreign_key_check").use { statement ->
                assertFalse(statement.step())
            }
            database.prepare("SELECT liked, likedDate, inLibrary, dateDownload, isLocal, localPath FROM song WHERE id='song-1'").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
                assertEquals(111L, it.getLong(1))
                assertEquals(222L, it.getLong(2))
                assertEquals(333L, it.getLong(3))
                assertEquals(1L, it.getLong(4))
                assertEquals("/music/song.mp3", it.getText(5))
            }
            database.prepare("SELECT contentLength FROM format WHERE id='song-1'").use {
                assertTrue(it.step())
                assertEquals(1234567L, it.getLong(0))
            }
        }
    }

    @Test
    fun everySupportedStartingSchemaCanMigrateToCurrentSchema() {
        val manualMigrations = listOf(
            MIGRATION_1_2, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_21_22,
        )
        for (version in 1..21) {
            instrumentation.targetContext.deleteDatabase(TEST_DATABASE)
            if (version == 1) createLegacyVersionOne() else helper.createDatabase(version).close()
            helper.runMigrationsAndValidate(22, manualMigrations).use { migrated ->
                migrated.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
            }
        }
    }

    @Test
    fun populatedLegacyVersionOnePreservesLikedSongArtistAndPlaylistLinks() {
        createLegacyVersionOne(populated = true)
        helper.runMigrationsAndValidate(22, listOf(
            MIGRATION_1_2, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_21_22,
        )).use { migrated ->
            migrated.prepare("SELECT title, liked, inLibrary FROM song WHERE id='Legacy12345'").use {
                assertTrue(it.step())
                assertEquals("Legacy Song", it.getText(0))
                assertEquals(1L, it.getLong(1))
                assertEquals(1000L, it.getLong(2))
            }
            migrated.prepare("SELECT artist.name FROM artist JOIN song_artist_map ON artist.id=artistId WHERE songId='Legacy12345'").use {
                assertTrue(it.step())
                assertEquals("Legacy Artist", it.getText(0))
                assertFalse(it.step())
            }
            migrated.prepare("SELECT playlist.name, path, songId FROM playlist JOIN playlist_song_map ON playlist.id=playlistId WHERE playlist.name='Legacy Playlist'").use {
                assertTrue(it.step())
                assertEquals("Legacy Playlist", it.getText(0))
                assertEquals("/", it.getText(1))
                assertEquals("Legacy12345", it.getText(2))
                assertFalse(it.step())
            }
            migrated.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
        }
    }

    private fun createLegacyVersionOne(populated: Boolean = false) {
        instrumentation.targetContext.deleteDatabase(TEST_DATABASE)
        // Upstream 8bb2735f3 replaced exported schema 1 with a different database lineage.
        // MIGRATION_1_2 supports the original schema preserved from that commit's parent.
        val schema = requireNotNull(javaClass.getResourceAsStream("/legacy-schema-1.json"))
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = instrumentation.targetContext.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (position in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(position).getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) database.execSQL(queries.getString(index))
            database.version = 1
            if (populated) {
                database.execSQL("INSERT INTO artist(id,name) VALUES (7,'Legacy Artist')")
                database.execSQL("INSERT INTO song(id,title,artistId,duration,liked,artworkType,isTrash,download_state,create_date,modify_date) VALUES ('Legacy12345','Legacy Song',7,123,1,0,0,0,1000,2000)")
                database.execSQL("INSERT INTO playlist(playlistId,name) VALUES (8,'Legacy Playlist')")
                database.execSQL("INSERT INTO playlist_song(id,playlistId,songId,idInPlaylist) VALUES (1,8,'Legacy12345',0)")
            }
        }
    }

    @Test
    fun freshAndMigratedFoldersBothRejectCaseInsensitiveDuplicates() {
        helper.createDatabase(21).close()
        helper.runMigrationsAndValidate(22, listOf(MIGRATION_21_22)).use { migrated ->
            migrated.execSQL("INSERT INTO playlist_folder(path) VALUES ('/Music/')")
            assertThrows(Exception::class.java) {
                migrated.execSQL("INSERT INTO playlist_folder(path) VALUES ('/music/')")
            }
            migrated.prepare("SELECT path FROM playlist_folder WHERE path='/MUSIC/'").use {
                assertTrue(it.step())
                assertEquals("/Music/", it.getText(0))
                assertFalse(it.step())
            }
        }
        val context = instrumentation.targetContext
        val name = "fresh-folder-parity"
        context.deleteDatabase(name)
        val fresh = InternalDatabase.newTestInstance(context, name)
        try {
            val sqlite = fresh.openHelper.writableDatabase
            sqlite.execSQL("INSERT INTO playlist_folder(path) VALUES ('/Music/')")
            assertThrows(Exception::class.java) {
                sqlite.execSQL("INSERT INTO playlist_folder(path) VALUES ('/music/')")
            }
            sqlite.query("SELECT path FROM playlist_folder WHERE path='/MUSIC/'").use {
                assertTrue(it.moveToFirst())
                assertEquals("/Music/", it.getString(0))
                assertFalse(it.moveToNext())
            }
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            fresh.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-21-22"
    }
}
