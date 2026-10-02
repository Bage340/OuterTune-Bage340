package com.dd3boh.outertune.playback

import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.R
import java.util.Collections
import java.util.IdentityHashMap

@StringRes
fun playbackErrorMessageResource(error: PlaybackException): Int {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    var messageResource = R.string.error_unknown
    // Media3 source errors wrap the resolver error through intermediate exceptions.
    while (cause != null && visited.add(cause)) {
        if (cause is PlaybackException) {
            when (cause.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> return R.string.error_no_internet
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return R.string.error_timeout
                PlaybackException.ERROR_CODE_REMOTE_ERROR -> messageResource = R.string.error_no_stream
            }
        }
        cause = cause.cause
    }
    return messageResource
}
