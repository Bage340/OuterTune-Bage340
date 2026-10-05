package com.dd3boh.outertune.ui.screens.settings.fragments

import android.app.Application
import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 33])
class ScannerMediaPermissionStateTest {
    private lateinit var application: Application
    private lateinit var permission: String

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        shadowOf(application).denyPermissions(permission)
    }

    @Test
    fun initializesFromActualPlatformPermission() {
        assertFalse(ScannerMediaPermissionState(application).granted)
        shadowOf(application).grantPermissions(permission)
        assertTrue(ScannerMediaPermissionState(application).granted)
    }

    @Test
    fun denyThenGrantCallbackUpdatesWithoutAnotherScanClick() {
        val state = ScannerMediaPermissionState(application)
        state.onPermissionResult(false)
        assertFalse(state.granted)
        shadowOf(application).grantPermissions(permission)
        state.onPermissionResult(true)
        assertTrue(state.granted)
    }

    @Test
    fun resumeRefreshesExternalGrantAndRevocation() {
        val owner = TestOwner()
        val state = ScannerMediaPermissionState(application)
        owner.registry.addObserver(state)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse(state.granted)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        shadowOf(application).grantPermissions(permission)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(state.granted)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        shadowOf(application).denyPermissions(permission)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse(state.granted)
    }

    @Test
    fun removalStopsLifecycleRefreshAndClickRefreshUsesCurrentPermission() {
        val owner = TestOwner()
        val state = ScannerMediaPermissionState(application)
        owner.registry.addObserver(state)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.removeObserver(state)
        assertEquals(0, owner.registry.observerCount)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        shadowOf(application).grantPermissions(permission)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse(state.granted)
        state.refresh()
        assertTrue(state.granted)
        shadowOf(application).denyPermissions(permission)
        state.refresh()
        assertFalse(state.granted)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
