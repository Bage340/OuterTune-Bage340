package com.dd3boh.outertune.playback

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalPlaybackUriTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun registerProvider(uri: Uri, audio: File?) {
        val provider = AudioProvider(audio)
        provider.attachInfo(context, ProviderInfo().apply { authority = uri.authority })
        ShadowContentResolver.registerProviderInternal(uri.authority!!, provider)
    }
    private fun song(path: String?, artwork: String? = null) = MediaMetadata(
        id = "LSIXAYsdfc", title = "Fixture", artists = emptyList(), duration = 1,
        genre = null, isLocal = true, localPath = path, thumbnailUrl = artwork,
    )

    @Test
    fun importedFileUriResolvesPhysicalAudioIncludingEscapedSpaces() {
        val audio = temporaryFolder.newFile("local audio.flac")
        val uri = Uri.fromFile(audio)
        assertEquals(uri, findLocalPlaybackUri(context.contentResolver, song(uri.toString()), null))
    }

    @Test
    fun importedLocalhostFileUriResolvesPhysicalAudio() {
        val audio = temporaryFolder.newFile("localhost audio.flac")
        val localUri = Uri.fromFile(audio)
        val reference = localUri.buildUpon().authority("localhost").build().toString()
        assertEquals(localUri, findLocalPlaybackUri(context.contentResolver, song(reference), null))
    }

    @Test
    fun importedContentUriResolvesOfflineAndPreservesSeekRequest() {
        val audio = temporaryFolder.newFile("fixture.flac").apply { writeBytes(byteArrayOf(10, 20, 30, 40, 50)) }
        val uri = Uri.parse("content://local-playback-fixture/audio/1")
        registerProvider(uri, audio)
        val metadata = song(uri.toString())
        val resolved = findLocalPlaybackUri(context.contentResolver, metadata, null)
        assertEquals(uri, resolved)
        assertEquals(PlaybackSourceKind.DATABASE_FILE,
            selectPlaybackSourceKind(false, resolved != null, true, true, isLocalPlayback(metadata.id, metadata, null)))
        val request = DataSpec.Builder().setUri("https://unreachable.invalid/audio").setKey(metadata.id)
            .setPosition(2).setLength(2).setUriPositionOffset(7)
            .setHttpRequestHeaders(mapOf("X-Fixture" to "retained")).build()
        val localRequest = requireNotNull(resolveLocalPlaybackDataSpec(request, context.contentResolver, metadata, null))
        assertEquals(uri, localRequest.uri)
        assertEquals(2L, localRequest.position)
        assertEquals(2L, localRequest.length)
        assertEquals(7L, localRequest.uriPositionOffset)
        assertEquals(metadata.id, localRequest.key)
        assertEquals(mapOf("X-Fixture" to "retained"), localRequest.httpRequestHeaders)
        val source = DefaultDataSource.Factory(context).createDataSource()
        try {
            assertEquals(2L, source.open(localRequest))
            val bytes = ByteArray(2)
            assertEquals(2, source.read(bytes, 0, 2))
            assertArrayEquals(byteArrayOf(30, 40), bytes)
            assertEquals(-1, source.read(bytes, 0, 2))
        } finally { source.close() }
    }

    @Test
    fun revokedContentAccessStaysMissingLocalWithoutNetworkFallback() {
        val uri = Uri.parse("content://local-playback-denied/audio/1")
        registerProvider(uri, null)
        val metadata = song(uri.toString())
        assertNull(findLocalPlaybackUri(context.contentResolver, metadata, null))
        assertEquals(PlaybackSourceKind.MISSING_LOCAL,
            selectPlaybackSourceKind(false, false, true, true, true))
    }

    @Test
    fun inaccessibleContentPathRecoversRetainedPhysicalAudio() {
        val audio = temporaryFolder.newFile("recovered.flac")
        val uri = Uri.parse("content://local-playback-recovery/audio/1")
        registerProvider(uri, null)
        assertEquals(Uri.fromFile(audio),
            findLocalPlaybackUri(context.contentResolver, song(uri.toString(), audio.path), null))
    }

    private class AudioProvider(private val audio: File?) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            audio?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: throw FileNotFoundException("Permission revoked or audio removed")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "audio/flac"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
