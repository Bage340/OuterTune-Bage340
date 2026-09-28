package com.dd3boh.outertune.ui.menu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class PlaylistLibraryActionCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val activePlaylists = mutableSetOf<String>()

    fun launchIfIdle(
        playlistId: String,
        onStarted: () -> Unit,
        operation: suspend () -> Unit,
        onFailure: (Exception) -> Unit
    ): Boolean {
        if (!synchronized(activePlaylists) { activePlaylists.add(playlistId) }) return false
        try {
            onStarted()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
            job.invokeOnCompletion {
                synchronized(activePlaylists) { activePlaylists.remove(playlistId) }
            }
            job.start()
        } catch (e: Throwable) {
            synchronized(activePlaylists) { activePlaylists.remove(playlistId) }
            throw e
        }
        return true
    }
}

internal val playlistLibraryActions = PlaylistLibraryActionCoordinator()
