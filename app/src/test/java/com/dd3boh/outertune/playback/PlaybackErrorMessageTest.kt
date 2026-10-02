package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import com.dd3boh.outertune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackErrorMessageTest {
    private fun wrapped(code: Int): PlaybackException {
        val resolverError = PlaybackException(
            "No validated stream\nmediaId=PocoMiss001, localPath=/private/music",
            null,
            code,
        )
        return ExoPlaybackException.createForSource(
            IOException("Unexpected obfuscated resolver", resolverError),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }

    @Test
    fun wrappedRemoteFailureShowsUnavailableStreamAndKeepsDiagnostics() {
        val error = wrapped(PlaybackException.ERROR_CODE_REMOTE_ERROR)

        assertEquals(R.string.error_no_stream, playbackErrorMessageResource(error))
        assertTrue(error.stackTraceToString().contains("mediaId=PocoMiss001"))
        assertTrue(error.stackTraceToString().contains("localPath=/private/music"))
    }

    @Test
    fun wrappedNetworkFailureShowsNetworkMessage() {
        assertEquals(
            R.string.error_no_internet,
            playbackErrorMessageResource(wrapped(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)),
        )
    }

    @Test
    fun wrappedTimeoutShowsTimeoutMessage() {
        assertEquals(
            R.string.error_timeout,
            playbackErrorMessageResource(wrapped(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)),
        )
    }

    @Test
    fun innerNetworkFailureTakesPrecedenceOverGenericRemoteWrapper() {
        val error = PlaybackException(
            "remote",
            wrapped(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )

        assertEquals(R.string.error_no_internet, playbackErrorMessageResource(error))
    }

    @Test
    fun unknownAndMissingLocalFailuresUseSafeFallbackWithoutParsingMessages() {
        assertEquals(
            R.string.error_unknown,
            playbackErrorMessageResource(
                PlaybackException("No validated stream", null, PlaybackException.ERROR_CODE_UNSPECIFIED),
            ),
        )
        assertEquals(
            R.string.error_unknown,
            playbackErrorMessageResource(wrapped(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)),
        )
    }

    @Test(timeout = 1000)
    fun cyclicCauseChainTerminatesWithSafeFallback() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        val error = PlaybackException("Source error", first, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        assertEquals(R.string.error_unknown, playbackErrorMessageResource(error))
    }
}
