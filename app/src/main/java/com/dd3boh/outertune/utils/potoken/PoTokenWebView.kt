package com.dd3boh.outertune.utils.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import com.dd3boh.outertune.constants.POTOKEN_DEBUG
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.dd3boh.outertune.BuildConfig
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class PoTokenWebView private constructor(context: Context) : PoTokenBackend {
    private val webView = WebView(context)
    private val scope = MainScope()
    private val initialization = PoTokenPendingRequests<Unit>()
    @Volatile
    private var initializationId: String? = null
    private val poTokenContinuations = PoTokenPendingRequests<String>()
    private val ownedWork = PoTokenOwnedWork(
        cancelScope = {
            scope.cancel()
            val error = PoTokenException("PoToken WebView closed")
            initialization.failAll(error)
            poTokenContinuations.failAll(error)
        },
        destroy = {
            val destroyOnMain = Runnable {
                webView.stopLoading()
                webView.clearHistory()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeJavascriptInterface(JS_INTERFACE)
                webView.removeAllViews()
                webView.destroy()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) destroyOnMain.run()
            else Handler(Looper.getMainLooper()).post(destroyOnMain)
        },
    )
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        onInitializationErrorCloseAndCancel(t)
    }
    private lateinit var expirationInstant: Instant

    //region Initialization
    init {
        val webViewSettings = webView.settings
        //noinspection SetJavaScriptEnabled we want to use JavaScript!
        webViewSettings.javaScriptEnabled = true
//        webViewSettings.safeBrowsingEnabled = false // sdk24 support
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webViewSettings, false)
        }
        webViewSettings.userAgentString = USER_AGENT
        webViewSettings.blockNetworkLoads = true // the WebView does not need internet access

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // There should not be any uncaught errors while executing the code, because
                    // everything that can fail is guarded by try-catch. Therefore, this likely
                    // indicates that there was a syntax error in the code, i.e. the WebView only
                    // supports a really old version of JS.

                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val exception = BadWebViewException(fmt)
                    Log.e(TAG, "This WebView implementation is broken: $fmt")

                    onInitializationErrorCloseAndCancel(exception)
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    override suspend fun initialize() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            initializationId = initialization.add(continuation) { close() }
            if (!ownedWork.isClosed) loadHtmlAndObtainBotguard()
        }
    }

    /**
     * Must be called right after instantiating [PoTokenWebView] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard() {
        if (POTOKEN_DEBUG) Log.d(TAG, "loadHtmlAndObtainBotguard() called")

        scope.launch(exceptionHandler) {
            val html = withContext(Dispatchers.IO) {
                webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            }

            // calls downloadAndRunBotguard() when the page has finished loading
            val data = html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>")
            webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null)
        }
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        if (POTOKEN_DEBUG) Log.d(TAG, "downloadAndRunBotguard() called")

        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsedChallengeData = parseChallengeData(responseBody)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null
            )
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Initialization error from JavaScript: $error")
        }
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        if (POTOKEN_DEBUG) Log.d(TAG, "botguardResponse: $botguardResponse")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", ${JSONObject.quote(botguardResponse)} ]",
        ) { responseBody ->
            if (POTOKEN_DEBUG) Log.d(TAG, "GenerateIT response: $responseBody")
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)

            // leave 10 minutes of margin just to be sure
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds).minus(10, ChronoUnit.MINUTES)

            webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                if (POTOKEN_DEBUG) Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
                initializationId?.let { initialization.complete(it, Result.success(Unit)) }
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    override suspend fun generatePoToken(identifier: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (POTOKEN_DEBUG) Log.d(TAG, "generatePoToken() called with identifier $identifier")
                val requestId = poTokenContinuations.add(cont)
                if (ownedWork.isClosed) {
                    poTokenContinuations.complete(requestId, Result.failure(PoTokenException("PoToken WebView closed")))
                    return@suspendCancellableCoroutine
                }
                if (!cont.isActive) return@suspendCancellableCoroutine
                webView.evaluateJavascript(
                    """(function () { try {
                        const requestId = ${JSONObject.quote(requestId)}
                        const u8Identifier = ${stringToU8(identifier)}
                        const poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        const poTokenU8String = poTokenU8.join(",")
                        $JS_INTERFACE.onObtainPoTokenResult(requestId, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(${JSONObject.quote(requestId)}, error + "\n" + error.stack)
                    } })()""",
                    null
                )
            }
        }
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(requestId: String, error: String) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        }
        poTokenContinuations.complete(requestId, Result.failure(buildExceptionForJsError(error)))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(requestId: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            poTokenContinuations.complete(requestId, Result.failure(t))
            return
        }

        poTokenContinuations.complete(requestId, Result.success(poToken))
    }

    override val isExpired: Boolean
        get() = ownedWork.isClosed || Instant.now().isAfter(expirationInstant)
    //endregion

    //region Utils
    /**
     * Makes a POST request to [url] with the given [data] by setting the correct headers. Calls
     * [onInitializationErrorCloseAndCancel] in case of any network errors and also if the response
     * does not have HTTP code 200, therefore this is supposed to be used only during
     * initialization. Calls [handleResponseBody] with the response body if the response is
     * successful. The backend owns each call until its response body has been consumed.
     */
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        scope.launch(exceptionHandler) {
            val requestBuilder = okhttp3.Request.Builder()
                .post(data.toRequestBody())
                .headers(mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json+protobuf",
                    "x-goog-api-key" to GOOGLE_API_KEY,
                    "x-user-agent" to "grpc-web-javascript/0.1",
                ).toHeaders())
                .url(url)
            val call = httpClient.newCall(requestBuilder.build())
            val cancellation = ownedWork.track { call.cancel() }
            try {
                val body = withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (response.code != 200) throw PoTokenException("Invalid response code: ${response.code}")
                        response.body?.string() ?: throw PoTokenException("Empty BotGuard response")
                    }
                }
                if (!ownedWork.isClosed) handleResponseBody(body)
            } finally {
                ownedWork.release(cancellation)
            }
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to the initialization waiter. Late or duplicate callbacks are harmless.
     */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        initializationId?.let { initialization.complete(it, Result.failure(error)) }
        poTokenContinuations.failAll(error)
        close()
    }

    /**
     * Releases all [webView] resources.
     */
    override fun close() = ownedWork.close()
    //endregion

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        private val httpClient = OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        internal suspend fun create(context: Context): PoTokenWebView {
            var candidate: PoTokenWebView? = null
            try {
                return withContext(Dispatchers.Main) {
                    PoTokenWebView(context).also { candidate = it }
                }
            } catch (error: Throwable) {
                candidate?.close()
                throw error
            }
        }
    }
}
