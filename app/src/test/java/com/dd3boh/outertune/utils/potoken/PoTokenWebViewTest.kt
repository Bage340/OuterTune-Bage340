package com.dd3boh.outertune.utils.potoken

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.LEGACY)
class PoTokenWebViewTest {
    @Test
    fun borrowedCachedBackendClosedBeforeTokenRecoversWithReplacement() = runBlocking {
        val webView = PoTokenWebView.create(ApplicationProvider.getApplicationContext())
        var creations = 0
        // Avoid network/JavaScript setup; the failing player request uses the real WebView backend.
        val cached = object : PoTokenBackend {
            override val isExpired = false
            override suspend fun initialize() = Unit
            override suspend fun generatePoToken(identifier: String): String {
                if (identifier != "video") return "cached:$identifier"
                webView.onJsInitializationError("late initialization failure")
                return webView.generatePoToken(identifier)
            }
            override fun close() = webView.close()
        }
        val replacement = object : PoTokenBackend {
            override val isExpired = false
            override suspend fun initialize() = Unit
            override suspend fun generatePoToken(identifier: String) = "recovered:$identifier"
            override fun close() = Unit
        }
        val coordinator = PoTokenCoordinator(5_000) { if (creations++ == 0) cached else replacement }
        try {
            coordinator.get("warmup", "session")
            val recovered = runCatching { coordinator.get("video", "session") }
            assertTrue("Internal disposal should retry: ${recovered.exceptionOrNull()}", recovered.isSuccess)
            assertEquals("recovered:video", recovered.getOrNull()?.playerRequestPoToken)
            assertEquals("recovered:session", recovered.getOrNull()?.streamingDataPoToken)
            assertEquals(2, creations)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            webView.close()
        }
    }

    @Test
    fun closedBackendFailsTokenAsOrdinaryErrorWhileCallerRemainsActive() = runBlocking {
        val backend = PoTokenWebView.create(ApplicationProvider.getApplicationContext())
        // A cached backend can be disposed after borrowing it but before Main runs the request.
        backend.onJsInitializationError("late initialization failure")
        val error = runCatching { backend.generatePoToken("video") }.exceptionOrNull()
        assertTrue("Disposed optional backend must allow retry or fallback: $error", error is PoTokenException)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun closingBackendFailsPendingTokenWithoutCancellingItsCaller() = runBlocking {
        val backend = PoTokenWebView.create(ApplicationProvider.getApplicationContext())
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                val error = runCatching { backend.generatePoToken("video") }.exceptionOrNull()
                assertTrue("Disposal must be an ordinary optional-token failure: $error", error is PoTokenException)
                assertTrue(currentCoroutineContext().isActive)
            }
            assertTrue("Request should be waiting for JavaScript", request.isActive)
            backend.close()
            request.await()
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            backend.close()
        }
    }

    @Test
    fun genuineCallerCancellationStillCancelsPendingToken() = runBlocking {
        val backend = PoTokenWebView.create(ApplicationProvider.getApplicationContext())
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                backend.generatePoToken("video")
            }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            backend.close()
        }
    }
}
