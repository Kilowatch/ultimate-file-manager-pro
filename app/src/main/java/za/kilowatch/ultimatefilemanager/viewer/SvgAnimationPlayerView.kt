package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Sandboxed, local-only player view for playing SMIL animated vector graphics (SVG and SVGZ)
 * in UFM Media Player without external network access or file-scheme vulnerabilities.
 */
class SvgAnimationPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView = WebView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var currentHtmlBytes: ByteArray? = null
    private var isPageLoaded: Boolean = false
    private var pendingAutoPlay: Boolean = true
    private var wasPlayingBeforePause: Boolean = false

    var isPlaying: Boolean = false
        private set
    var currentPositionMs: Long = 0L
        private set
    var totalDurationMs: Long = 0L
        private set
    val durationMs: Long
        get() = totalDurationMs
    var isIndeterminateDuration: Boolean = true
        private set
    var isRepeat: Boolean = false

    var onProgressUpdate: ((positionMs: Long, totalDurationMs: Long, isIndeterminate: Boolean) -> Unit)? = null
    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onError: ((error: Throwable) -> Unit)? = null

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/player/") { path ->
            if (path == "index.html" || path.isEmpty()) {
                val bytes = currentHtmlBytes
                if (bytes != null) {
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(bytes))
                } else {
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            } else null
        }
        .build()

    private val progressPollRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && isPageLoaded) {
                webView.evaluateJavascript("document.querySelector('svg')?.getCurrentTime() || 0") { result ->
                    val sec = result?.toDoubleOrNull() ?: 0.0
                    val posMs = (sec * 1000.0).toLong()
                    currentPositionMs = posMs

                    if (!isIndeterminateDuration && totalDurationMs > 0L && posMs >= totalDurationMs) {
                        if (isRepeat) {
                            seekTo(0L)
                        } else {
                            pause()
                        }
                    }
                    onProgressUpdate?.invoke(currentPositionMs, totalDurationMs, isIndeterminateDuration)
                }
                handler.postDelayed(this, 250L)
            }
        }
    }

    init {
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        configureWebView()
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.BLACK)

        CookieManager.getInstance().setAcceptCookie(false)

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.host.equals("appassets.androidplatform.net", ignoreCase = true)) {
                    val intercepted = assetLoader.shouldInterceptRequest(url)
                    if (intercepted != null) {
                        return intercepted
                    }
                    // Return empty 204 for unhandled subresources under appassets domain (e.g. favicon)
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        204,
                        "No Content",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                // Unconditionally block any external request
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    403,
                    "Forbidden",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Block all navigation attempts
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                if (pendingAutoPlay) {
                    play()
                } else {
                    pause()
                }
                onProgressUpdate?.invoke(currentPositionMs, totalDurationMs, isIndeterminateDuration)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    onError?.invoke(Exception("SVG render error: ${error.description}"))
                }
            }
        }
    }

    /**
     * Loads decompressed SVG bytes into the existing WebView instance.
     * Can be invoked repeatedly during playlist navigation without recreating the view.
     */
    fun loadSvg(svgBytes: ByteArray, autoPlay: Boolean = true) {
        try {
            stopProgressPolling()
            val decompressed = SvgAnimationHelper.decompressIfNeeded(svgBytes)
            val svgString = String(decompressed, Charsets.UTF_8)

            val parsedDuration = SvgAnimationHelper.parseAnimationDurationMs(svgString)
            if (parsedDuration != null && parsedDuration > 0L) {
                totalDurationMs = parsedDuration
                isIndeterminateDuration = false
            } else {
                totalDurationMs = 0L
                isIndeterminateDuration = true
            }

            currentPositionMs = 0L
            isPageLoaded = false
            pendingAutoPlay = autoPlay

            val htmlString = SvgAnimationHelper.wrapSvgInHtml(svgString)
            currentHtmlBytes = htmlString.toByteArray(Charsets.UTF_8)

            webView.loadUrl("https://appassets.androidplatform.net/player/index.html")
        } catch (e: Throwable) {
            onError?.invoke(e)
        }
    }

    /**
     * Starts or resumes SMIL animation playback.
     */
    fun play() {
        isPlaying = true
        if (isPageLoaded) {
            webView.evaluateJavascript("document.querySelector('svg')?.unpauseAnimations();", null)
            startProgressPolling()
        }
        onPlaybackStateChanged?.invoke(true)
    }

    /**
     * Pauses SMIL animation playback.
     */
    fun pause() {
        isPlaying = false
        if (isPageLoaded) {
            webView.evaluateJavascript("document.querySelector('svg')?.pauseAnimations();", null)
        }
        stopProgressPolling()
        onPlaybackStateChanged?.invoke(false)
    }

    /**
     * Toggles between play and pause.
     */
    fun toggle() {
        if (isPlaying) pause() else play()
    }

    /**
     * Restarts playback from timestamp 0.
     */
    fun restart() {
        seekTo(0L)
        play()
    }

    /**
     * Seeks to the specified position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        val clampedPos = if (!isIndeterminateDuration && totalDurationMs > 0L) {
            positionMs.coerceIn(0L, totalDurationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        currentPositionMs = clampedPos
        val sec = clampedPos / 1000f
        if (isPageLoaded) {
            webView.evaluateJavascript("document.querySelector('svg')?.setCurrentTime($sec);", null)
        }
        onProgressUpdate?.invoke(currentPositionMs, totalDurationMs, isIndeterminateDuration)
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        handler.post(progressPollRunnable)
    }

    private fun stopProgressPolling() {
        handler.removeCallbacks(progressPollRunnable)
    }

    /**
     * Handles Activity onPause / backgrounding.
     */
    fun onPause() {
        wasPlayingBeforePause = isPlaying
        pause()
        webView.onPause()
        webView.pauseTimers()
    }

    /**
     * Handles Activity onResume / foregrounding.
     */
    fun onResume() {
        webView.onResume()
        if (wasPlayingBeforePause) {
            play()
        }
    }

    /**
     * Cleans up WebView resources on Activity destruction.
     */
    fun destroy() {
        stopProgressPolling()
        currentHtmlBytes = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
}
