package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupScanTimestampPolicyTest {
    @Test
    fun failedEnabledLocalScanPreservesPreviousTimestamp() {
        assertEquals(
            PREVIOUS_TIMESTAMP,
            updatedStartupLastLocalScan(
                previousTimestamp = PREVIOUS_TIMESTAMP,
                scanTimestamp = SCAN_TIMESTAMP,
                localLibraryEnabled = true,
                localScanSucceeded = false,
            ),
        )
    }

    @Test
    fun successfulEnabledLocalScanAdvancesTimestamp() {
        assertEquals(
            SCAN_TIMESTAMP,
            updatedStartupLastLocalScan(
                previousTimestamp = PREVIOUS_TIMESTAMP,
                scanTimestamp = SCAN_TIMESTAMP,
                localLibraryEnabled = true,
                localScanSucceeded = true,
            ),
        )
    }

    @Test
    fun disabledLocalLibraryRecordsCompletedDownloadsScanTimestamp() {
        assertEquals(
            SCAN_TIMESTAMP,
            updatedStartupLastLocalScan(
                previousTimestamp = PREVIOUS_TIMESTAMP,
                scanTimestamp = SCAN_TIMESTAMP,
                localLibraryEnabled = false,
                localScanSucceeded = false,
            ),
        )
    }

    private companion object {
        const val PREVIOUS_TIMESTAMP = 123L
        const val SCAN_TIMESTAMP = 456L
    }
}
