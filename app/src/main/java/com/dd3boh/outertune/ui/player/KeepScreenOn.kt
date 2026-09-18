package com.dd3boh.outertune.ui.player

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dd3boh.outertune.constants.KeepScreenOn

internal enum class KeepScreenOnRequest {
    PLAYER,
    LYRICS,
}

@Stable
internal class KeepScreenOnRequestState {
    private val requests = mutableStateMapOf<Any, KeepScreenOnRequest>()

    val playerVisible: Boolean
        get() = KeepScreenOnRequest.PLAYER in requests.values

    val lyricsVisible: Boolean
        get() = KeepScreenOnRequest.LYRICS in requests.values

    fun update(owner: Any, request: KeepScreenOnRequest, active: Boolean) {
        if (active) {
            requests[owner] = request
        } else {
            requests.remove(owner)
        }
    }

    fun remove(owner: Any) {
        requests.remove(owner)
    }
}

internal val LocalKeepScreenOnRequestState = staticCompositionLocalOf<KeepScreenOnRequestState> {
    error("No KeepScreenOnRequestState provided")
}

@Composable
internal fun rememberKeepScreenOnRequestState(): KeepScreenOnRequestState =
    remember { KeepScreenOnRequestState() }

@Composable
internal fun KeepScreenOnRequestEffect(
    request: KeepScreenOnRequest,
    active: Boolean,
) {
    val requestState = LocalKeepScreenOnRequestState.current
    val owner = remember { Any() }

    DisposableEffect(requestState, owner, request, active) {
        requestState.update(owner, request, active)
        onDispose { requestState.remove(owner) }
    }
}

internal fun shouldKeepScreenOn(
    mode: KeepScreenOn,
    isPlaying: Boolean,
    playerVisible: Boolean,
    lyricsVisible: Boolean,
    lifecycleResumed: Boolean,
): Boolean = lifecycleResumed && isPlaying && playerVisible && when (mode) {
    KeepScreenOn.NEVER -> false
    KeepScreenOn.LYRICS -> lyricsVisible
    KeepScreenOn.PLAYER -> true
}

@Composable
internal fun KeepScreenOnEffect(
    lifecycle: Lifecycle,
    mode: KeepScreenOn,
    isPlaying: Boolean,
    requestState: KeepScreenOnRequestState,
    view: View = LocalView.current,
) {
    var lifecycleResumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val keepScreenOn = shouldKeepScreenOn(
        mode = mode,
        isPlaying = isPlaying,
        playerVisible = requestState.playerVisible,
        lyricsVisible = requestState.lyricsVisible,
        lifecycleResumed = lifecycleResumed,
    )

    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}
