package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL

internal class ScannerMediaPermissionState(private val context: Context) : LifecycleEventObserver {
    var granted by mutableStateOf(hasPermission())
        private set

    fun onPermissionResult(isGranted: Boolean) {
        granted = isGranted
    }

    fun refresh() {
        granted = hasPermission()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) refresh()
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED
}
