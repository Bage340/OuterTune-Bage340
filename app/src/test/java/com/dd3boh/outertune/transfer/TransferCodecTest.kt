package com.dd3boh.outertune.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferCodecTest {
    private val youtube = TransferTrack(TrackSource.YOUTUBE, "AbCdEf12345", "A, \"title\"", listOf("One", "Two"), "Album", 187, null, true, true)
    private val local = TransferTrack(TrackSource.LOCAL, "LS123", "Local\ntrack", listOf("Artist"), null, 45, "content://media/external/audio/42", false, true)
    private val document = TransferDocument(listOf(youtube, local), listOf(TransferPlaylist("playlist-1", "Favorites", listOf(youtube, local))))

    @Test fun jsonRoundTripAndVersionRejection() {
        assertEquals(document, TransferCodec.decode(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, document)))
        val encoded = TransferCodec.encode(TransferFormat.JSON, document).toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.JSON, encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray()) }
    }

    @Test fun bookmarkedOnlinePlaylistKeepsSourceAndBrowseIdAcrossFormats() {
        val playlist = TransferPlaylist("online-row", "Online mix", listOf(youtube), isLocal = false,
            browseId = "VLonline123", bookmarked = true)
        val source = TransferDocument(emptyList(), listOf(playlist))
        for (format in TransferFormat.entries) {
            assertEquals(source, TransferCodec.decode(format, TransferCodec.encode(format, source)))
        }
    }

    @Test fun csvQuotesCommasAndMultilineRoundTrip() {
        val encoded = TransferCodec.encode(TransferFormat.CSV, document)
        assertEquals(document, TransferCodec.decode(TransferFormat.CSV, encoded))
        val text = encoded.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"A, \"\"title\"\"\""))
        assertTrue(text.contains("\"Local\ntrack\""))
    }

    @Test fun csvFormulaCellsAreNeutralizedWithoutChangingDecodedValues() {
        val dangerous = youtube.copy(stableId = "-AbCdEf1234", title = "  =HYPERLINK(\"https://bad.test\")", album = "+SUM(1)", artists = listOf("@cmd"))
        val source = TransferDocument(listOf(dangerous), listOf(TransferPlaylist("-playlist", "-unsafe", listOf(dangerous))))
        val csv = TransferCodec.encode(TransferFormat.CSV, source).toString(Charsets.UTF_8)
        assertTrue(csv.contains("'-AbCdEf1234"))
        assertTrue(csv.contains("'  =HYPERLINK"))
        assertTrue(csv.contains("'+SUM(1)"))
        assertTrue(csv.contains("'-unsafe"))
        assertEquals(source, TransferCodec.decode(TransferFormat.CSV, csv.toByteArray(Charsets.UTF_8)))
    }

    @Test fun m3uRoundTripAndYouTubeUrlVariants() {
        val playlistOnly = TransferDocument(emptyList(), document.playlists)
        val encoded = TransferCodec.encode(TransferFormat.M3U8, playlistOnly)
        assertEquals(playlistOnly, TransferCodec.decode(TransferFormat.M3U8, encoded))
        val external = "#EXTM3U\n#EXTINF:187,One - Song\nhttps://music.youtube.com/watch?foo=bar&v=AbCdEf12345&t=5\n#EXTINF:22,Other - Short\nhttps://youtu.be/ZyXwVu98765?si=x\n"
        val parsed = TransferCodec.decode(TransferFormat.M3U8, external.toByteArray())
        assertEquals(listOf("AbCdEf12345", "ZyXwVu98765"), parsed.playlists.single().tracks.map { it.stableId })
        assertEquals(listOf(TrackSource.YOUTUBE, TrackSource.YOUTUBE), parsed.playlists.single().tracks.map { it.source })
    }

    @Test fun malformedAndOversizeInputNeverReturnsPartialDocument() {
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.CSV, "schemaVersion,collection\n\"unterminated".toByteArray()) }
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.JSON, "{".toByteArray()) }
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.M3U8, "#EXTM3U\n../../escape.mp3\n".toByteArray()) }
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.M3U8, "#EXTM3U\nhttps://youtube.com.evil.test/watch?v=AbCdEf12345\n".toByteArray()) }
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.M3U8, "#EXTM3U\nfile://remote-server/music.mp3\n".toByteArray()) }
        assertThrows(TransferException::class.java) { TransferCodec.decode(TransferFormat.JSON, ByteArray(TransferLimits.MAX_BYTES + 1)) }
    }

    @Test fun m3uRejectsExcessiveEmptyLinesWithoutBuildingAnUnboundedLineList() {
        val flood = "#EXTM3U\n" + "\n".repeat(TransferLimits.MAX_TRACKS * 4 + 1025)
        assertThrows(TransferException::class.java) {
            TransferCodec.decode(TransferFormat.M3U8, flood.toByteArray())
        }
    }

    @Test fun csvRejectsWideRowsBeforeAccumulatingThousandsOfCells() {
        val validHeader = TransferCodec.encode(TransferFormat.CSV, TransferDocument(emptyList(), emptyList()))
            .toString(Charsets.UTF_8)
        assertThrows(TransferException::class.java) {
            TransferCodec.decode(TransferFormat.CSV, (validHeader + ",".repeat(15_000) + "\n").toByteArray())
        }
    }

    @Test fun existingM3uLocalLocatorRetainsExactPathAndDoesNotGuessFromTitle() {
        val legacy = "#EXTM3U\n#EXTINF:45,Artist - Local\nLS123, C:\\Music\\local, track.mp3\n"
        val track = TransferCodec.decode(TransferFormat.M3U8, legacy.toByteArray()).playlists.single().tracks.single()
        assertEquals(TrackSource.LOCAL, track.source)
        assertEquals("LS123", track.stableId)
        assertEquals("C:\\Music\\local, track.mp3", track.localUri)
        assertEquals(TransferDisposition.UNRESOLVED, TransferStaging.stage(
            TransferDocument(emptyList(), listOf(TransferPlaylist("p", "p", listOf(track)))),
            emptySet(), emptySet(),
        ).playlists.single().tracks.single().disposition)
    }

    @Test fun relativeLocalM3uEntryIsUnresolvedWithoutDiscardingOtherTracks() {
        val content = "#EXTM3U\n#EXTINF:45,Artist - Relative\nMusic/song.mp3\nhttps://youtu.be/AbCdEf12345\n"
        val parsed = TransferCodec.decode(TransferFormat.M3U8, content.toByteArray())
        val tracks = parsed.playlists.single().tracks
        assertEquals(2, tracks.size)
        assertEquals(TrackSource.LOCAL, tracks[0].source)
        assertNull(tracks[0].localUri)
        assertEquals(TrackSource.YOUTUBE, tracks[1].source)
        val staged = TransferStaging.stage(parsed, emptySet(), emptySet())
        assertEquals(TransferDisposition.UNRESOLVED, staged.playlists.single().tracks[0].disposition)
        assertEquals(TransferDisposition.CREATE, staged.playlists.single().tracks[1].disposition)
    }

    @Test fun stagingMatchesOnlyExactIdentityAndMarksInaccessibleLocalUnresolved() {
        val localUri = requireNotNull(local.localUri)
        val staged = TransferStaging.stage(document, setOf(TrackIdentity(TrackSource.YOUTUBE, youtube.stableId)), setOf(localUri))
        assertEquals(TransferDisposition.EXISTING, staged.library[0].disposition)
        assertEquals(TransferDisposition.CREATE, staged.library[1].disposition)
        val unresolved = TransferStaging.stage(document, emptySet(), emptySet())
        assertEquals(TransferDisposition.UNRESOLVED, unresolved.library[1].disposition)
        assertEquals(TransferDisposition.CREATE, unresolved.library[0].disposition)
        val exactLocal = TransferStaging.stage(document, setOf(TrackIdentity(TrackSource.LOCAL, localUri)), setOf(localUri))
        assertEquals(TransferDisposition.EXISTING, exactLocal.library[1].disposition)
        val sameTitleWrongId = TransferStaging.stage(document, setOf(TrackIdentity(TrackSource.YOUTUBE, "Different00")), emptySet())
        assertEquals(TransferDisposition.CREATE, sameTitleWrongId.library[0].disposition)
    }

    @Test fun repeatedIdentityIsFlaggedAndNotCreatedTwice() {
        val repeated = TransferDocument(listOf(youtube, youtube), listOf(TransferPlaylist("p", "P", listOf(youtube))))
        val staged = TransferStaging.stage(repeated, emptySet(), emptySet())
        assertEquals(TransferDisposition.CREATE, staged.library[0].disposition)
        assertEquals(TransferDisposition.DUPLICATE, staged.library[1].disposition)
        assertEquals(TransferDisposition.DUPLICATE, staged.playlists.single().tracks.single().disposition)
        assertEquals(2, staged.duplicateCount)
    }

    @Test fun localSongWithoutPortablePathRemainsUnresolved() {
        val unavailable = local.copy(localUri = null)
        val source = TransferDocument(listOf(unavailable), emptyList())
        assertEquals(source, TransferCodec.decode(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, source)))
        assertEquals(TransferDisposition.UNRESOLVED, TransferStaging.stage(source, emptySet(), emptySet()).library.single().disposition)
        assertThrows(TransferException::class.java) {
            TransferCodec.encode(TransferFormat.M3U8, TransferDocument(emptyList(), listOf(TransferPlaylist("p", "P", listOf(unavailable)))))
        }
    }

    @Test fun largerCollectionsRoundTripWithinLimits() {
        for (count in listOf(100, 1000, 5000)) {
            val tracks = (0 until count).map { youtube.copy(stableId = "%011d".format(it)) }
            val source = TransferDocument(tracks, emptyList())
            assertEquals(source, TransferCodec.decode(TransferFormat.JSON, TransferCodec.encode(TransferFormat.JSON, source)))
            assertEquals(source, TransferCodec.decode(TransferFormat.CSV, TransferCodec.encode(TransferFormat.CSV, source)))
            val playlist = TransferDocument(emptyList(), listOf(TransferPlaylist("bulk", "Bulk", tracks)))
            assertEquals(playlist, TransferCodec.decode(TransferFormat.M3U8, TransferCodec.encode(TransferFormat.M3U8, playlist)))
        }
    }

    @Test fun deterministicExportAndInvalidUtf8() {
        for (format in TransferFormat.entries) {
            val source = if (format == TransferFormat.M3U8) TransferDocument(emptyList(), document.playlists) else document
            assertArrayEquals(TransferCodec.encode(format, source), TransferCodec.encode(format, source))
            assertThrows(TransferException::class.java) { TransferCodec.decode(format, byteArrayOf(0xC3.toByte(), 0x28)) }
        }
    }

    @Test fun entryAndFieldCapsRejectBeforeExport() {
        assertThrows(TransferException::class.java) {
            TransferCodec.encode(TransferFormat.JSON, TransferDocument(List(TransferLimits.MAX_TRACKS + 1) { youtube }, emptyList()))
        }
        assertThrows(TransferException::class.java) {
            TransferCodec.encode(TransferFormat.CSV, TransferDocument(listOf(youtube.copy(title = "x".repeat(TransferLimits.MAX_FIELD_CHARS + 1))), emptyList()))
        }
    }
}
