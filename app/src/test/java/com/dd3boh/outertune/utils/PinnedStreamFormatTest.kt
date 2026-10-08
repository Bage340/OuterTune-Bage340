package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedStreamFormatTest {
    @Test
    fun fallbackClientAndChangedQualityKeepTheCachedItagOrReturnNoCompatibleFormat() {
        val higherQuality = format(251)
        val lowerQuality = format(250)
        assertEquals(listOf(lowerQuality), YTPlayerUtils.formatsForPinnedItag(listOf(higherQuality, lowerQuality), 250))
        assertEquals(listOf(lowerQuality), YTPlayerUtils.formatsForPinnedItag(listOf(lowerQuality, higherQuality), 250))
        assertTrue(YTPlayerUtils.formatsForPinnedItag(listOf(higherQuality), 250).isEmpty())
        assertEquals(listOf(higherQuality, lowerQuality), YTPlayerUtils.formatsForPinnedItag(listOf(higherQuality, lowerQuality), null))
    }

    private fun format(itag: Int) = PlayerResponse.StreamingData.Format(
        itag = itag, url = "https://example.test/audio", mimeType = "audio/webm", bitrate = itag,
        width = null, height = null, contentLength = 8, quality = "tiny", fps = null,
        qualityLabel = null, averageBitrate = null, audioQuality = null, approxDurationMs = null,
        audioSampleRate = null, audioChannels = null, loudnessDb = null, lastModified = null, signatureCipher = null,
    )
}
