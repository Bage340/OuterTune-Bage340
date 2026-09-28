package com.dd3boh.outertune.ui.dialog

import com.dd3boh.outertune.transfer.TransferException
import com.dd3boh.outertune.transfer.TransferFormat
import com.dd3boh.outertune.transfer.TransferLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class LibraryTransferHostTest {
    @Test
    fun contentHeaderTakesPrecedenceOverMisleadingFileName() {
        assertEquals(
            TransferFormat.M3U8,
            detectTransferFormat("playlist.json", "application/json", "#EXTM3U\n".toByteArray()),
        )
        assertEquals(
            TransferFormat.CSV,
            detectTransferFormat(null, null, "schemaVersion,kind,playlistId\n".toByteArray()),
        )
    }

    @Test
    fun filenameAndMimeProvideFallbacks() {
        assertEquals(TransferFormat.JSON, detectTransferFormat("library.JSON", null, byteArrayOf()))
        assertEquals(TransferFormat.CSV, detectTransferFormat(null, "text/csv", byteArrayOf()))
        assertThrows(TransferException::class.java) { detectTransferFormat(null, null, byteArrayOf()) }
    }

    @Test
    fun readRejectsBytesBeyondLimitBeforeParsing() {
        val allowed = ByteArray(TransferLimits.MAX_BYTES)
        assertArrayEquals(allowed, readBoundedDocument(ByteArrayInputStream(allowed)))
        assertThrows(TransferException::class.java) {
            readBoundedDocument(ByteArrayInputStream(ByteArray(TransferLimits.MAX_BYTES + 1)))
        }
    }

    @Test
    fun readRejectsProviderThatNeverMakesProgress() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertThrows(IOException::class.java) { readBoundedDocument(stalled) }
    }
}
