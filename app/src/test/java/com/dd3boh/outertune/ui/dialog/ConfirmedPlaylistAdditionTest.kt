package com.dd3boh.outertune.ui.dialog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ConfirmedPlaylistAdditionTest {
    @Test fun allConfirmationChoicesSendTheSameEffectiveSongsToBothStores() = runBlocking {
        val songs = listOf("A", "B", "B")
        for (skip in listOf(emptySet(), setOf("A"))) {
            val events = mutableListOf<Pair<String, List<String>>>()
            addConfirmedPlaylistSongs(songs, skip,
                remoteAdd = { events += "remote" to it },
                localAdd = { events += "local" to it },
            )
            val expected = if (skip.isEmpty()) songs else listOf("B", "B")
            assertEquals(listOf("remote" to expected, "local" to expected), events)
        }
    }

    @Test fun remoteFailureOrCancellationNeverCommitsLocalSuccess() = runBlocking {
        for (failure in listOf(IllegalStateException("remote failed"), CancellationException("cancelled"))) {
            var localWrites = 0
            try {
                addConfirmedPlaylistSongs(listOf("A"),
                    remoteAdd = { throw failure },
                    localAdd = { localWrites++ },
                )
                fail("Remote failure must propagate")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(0, localWrites)
        }
    }

    @Test fun skippingEveryDuplicateDoesNotSendAnEmptyWrite() = runBlocking {
        var writes = 0
        addConfirmedPlaylistSongs(listOf("A"), setOf("A"),
            remoteAdd = { writes++ }, localAdd = { writes++ },
        )
        assertEquals(0, writes)
    }
}
