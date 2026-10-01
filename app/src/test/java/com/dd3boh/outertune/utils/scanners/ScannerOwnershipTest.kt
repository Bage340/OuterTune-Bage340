package com.dd3boh.outertune.utils.scanners

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dd3boh.outertune.constants.ScannerImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScannerOwnershipTest {
    private val context: Application by lazy { ApplicationProvider.getApplicationContext() }

    @Before
    fun setUp() = runBlocking {
        LocalMediaScanner.destroyScanner(FIRST_OWNER)
    }

    @After
    fun tearDown() = runBlocking {
        LocalMediaScanner.destroyScanner(FIRST_OWNER)
    }

    @Test
    fun secondOwnerCannotReplaceAnActiveScannersOwner() {
        LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, FIRST_OWNER)
        LocalMediaScanner.scannerState.value = 2

        assertThrows(ScannerAbortException::class.java) {
            LocalMediaScanner.getScanner(context, ScannerImpl.MEDIASTORE, SECOND_OWNER)
        }

        runBlocking { LocalMediaScanner.destroyScanner(SECOND_OWNER) }
        assertEquals(2, LocalMediaScanner.scannerState.value)
    }

    @Test
    fun sameOwnerCannotStartASecondScanBeforeTheFirstSetsItsState() {
        LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, FIRST_OWNER)
        LocalMediaScanner.scannerState.value = 0
        assertEquals(0, LocalMediaScanner.scannerState.value)

        assertThrows(ScannerAbortException::class.java) {
            LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, FIRST_OWNER)
        }
    }

    private companion object {
        const val FIRST_OWNER = 91
        const val SECOND_OWNER = 92
    }
}
