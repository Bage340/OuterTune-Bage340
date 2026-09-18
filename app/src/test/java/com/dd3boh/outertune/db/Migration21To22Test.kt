package com.dd3boh.outertune.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                """INSERT INTO song (id, title, duration, liked) VALUES ('song-1', 'Song', 123, 0)"""
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
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-21-22"
    }
}
