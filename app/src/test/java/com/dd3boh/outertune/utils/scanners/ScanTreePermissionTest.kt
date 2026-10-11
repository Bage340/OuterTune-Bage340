package com.dd3boh.outertune.utils.scanners

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ScanTreePermissionTest {
    private val music = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
    private val other = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Other")

    @Test fun restoredPathsWithoutThisPackagesGrantRequestTheFirstMissingTree() {
        assertEquals(music, scanTreeNeedingPermission(contextWithReadAccess(emptySet()), listOf(music)))
    }

    @Test fun oneReadableRootCannotHideAMissingSecondGrant() {
        assertEquals(other, scanTreeNeedingPermission(contextWithReadAccess(setOf(music)), listOf(music, other)))
    }

    @Test fun readOnlyTreeGrantIsEnoughForScanning() {
        assertNull(scanTreeNeedingPermission(contextWithReadAccess(setOf(music)), listOf(music)))
    }

    @Test fun mediaStoreWithoutSelectedTreesNeedsNoTreeGrant() {
        assertNull(scanTreeNeedingPermission(contextWithReadAccess(emptySet()), emptyList()))
    }

    private fun contextWithReadAccess(readable: Set<Uri>): ContextWrapper {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return object : ContextWrapper(app) {
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int {
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority!!, DocumentsContract.getTreeDocumentId(uri))
                return if (modeFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION && tree in readable) {
                    PackageManager.PERMISSION_GRANTED
                } else PackageManager.PERMISSION_DENIED
            }
        }
    }
}
