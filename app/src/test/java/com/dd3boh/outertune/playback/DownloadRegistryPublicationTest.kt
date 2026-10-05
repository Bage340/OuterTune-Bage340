package com.dd3boh.outertune.playback

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloadRegistryPublicationTest {
    @Test fun registryWaitsForDatabaseCommitAndPublishesAnIndependentMap() = runBlocking {
        val blocked = AtomicBoolean(false)
        val transactionStarted = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val delegate = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .setTransactionExecutor { command ->
                executor.execute {
                    if (blocked.get()) {
                        transactionStarted.countDown()
                        check(releaseTransaction.await(10, TimeUnit.SECONDS))
                    }
                    command.run()
                }
            }.build()
        val database = MusicDatabase(delegate)
        val date = LocalDateTime.of(2026, 9, 1, 12, 0)
        val initial = mapOf("song" to date)
        val registry = MutableStateFlow(initial)
        val states = mutableMapOf<String, LocalDateTime?>("song" to null)
        try {
            database.insert(SongEntity("song", "Fixture", localPath = "/old/song.mka", dateDownload = date, liked = true))
            blocked.set(true)
            val update = async(Dispatchers.IO) {
                publishDownloadRegistry(database, states, emptyMap(), setOf("song"), registry)
            }
            assertTrue(transactionStarted.await(5, TimeUnit.SECONDS))
            assertFalse(update.isCompleted)
            assertEquals(initial, registry.value)
            releaseTransaction.countDown()
            update.await()

            assertTrue(registry.value.isEmpty())
            states["song"] = date
            assertTrue(registry.value.isEmpty())
            val song = database.song("song").first()!!.song
            assertEquals(null, song.dateDownload)
            assertEquals(null, song.localPath)
            assertTrue(song.liked)
            assertEquals("Fixture", song.title)
        } finally {
            releaseTransaction.countDown()
            database.close()
            executor.shutdownNow()
        }
    }
}
