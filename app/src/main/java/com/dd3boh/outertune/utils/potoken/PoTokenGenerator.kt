package com.dd3boh.outertune.utils.potoken

import android.util.Log
import android.webkit.CookieManager
import com.dd3boh.outertune.App

class PoTokenGenerator {
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    @Volatile
    private var webViewBadImpl = false
    private val coordinator = PoTokenCoordinator { PoTokenWebView.create(App.instance) }

    /**
     * Tokens are bound to [visitorData], which must also identify the player and stream requests.
     * A lost WebView callback consumes at most the optional-token budget and returns null so the
     * caller can continue resolving a stream without tokens. Caller cancellation propagates.
     */
    suspend fun getWebClientPoToken(videoId: String, visitorData: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            coordinator.get(videoId, visitorData)
        } catch (error: BadWebViewException) {
            Log.e("PoTokenGenerator", "Could not obtain poToken because WebView is broken", error)
            webViewBadImpl = true
            null
        }
    }
}
