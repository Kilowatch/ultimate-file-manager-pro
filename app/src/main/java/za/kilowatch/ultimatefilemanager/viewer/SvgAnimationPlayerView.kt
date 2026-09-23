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
 *
 * Supports two animation engines:
 * - **SMIL**: Uses `pauseAnimations()`, `unpauseAnimations()`, `setCurrentTime()`, `getCurrentTime()`
 * - **CSS @keyframes**: Uses the Web Animations API (`getAnimations()`, `.pause()`, `.play()`, `.currentTime`)
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
    var isInteractiveSvg: Boolean = false
        private set
    /** True when the SVG uses CSS `@keyframes` animations instead of (or alongside) SMIL. */
    var hasCssAnimation: Boolean = false
        private set
    var detectedViews: List<String> = emptyList()
        private set
    var currentViewIndex: Int = -1
        private set
    var playbackRate: Float = 1.0f
        private set

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
                if (hasCssAnimation) {
                    // Query CSS animation state via Web Animations API
                    webView.evaluateJavascript("""
                        (function() {
                            var anims = document.getAnimations ? document.getAnimations() : [];
                            if (anims.length === 0) return '0,0,0';
                            var a = anims[0];
                            var ct = a.currentTime || 0;
                            var dur = 0;
                            var isInf = false;
                            try {
                                var timing = a.effect && a.effect.getComputedTiming ? a.effect.getComputedTiming() : null;
                                if (timing) {
                                    dur = timing.duration || 0;
                                    isInf = (timing.iterations === Infinity);
                                }
                            } catch(e) {}
                            if (dur > 0 && isInf) {
                                ct = ct % dur;
                            }
                            return ct + ',' + dur + ',' + (isInf ? '1' : '0');
                        })()
                    """.trimIndent()) { result ->
                        val clean = result?.trim()?.removeSurrounding("\"") ?: "0,0,0"
                        val parts = clean.split(",")
                        val posMs = parts.getOrNull(0)?.toDoubleOrNull()?.toLong() ?: 0L
                        val durMs = parts.getOrNull(1)?.toDoubleOrNull()?.toLong() ?: 0L
                        val isInf = parts.getOrNull(2) == "1"

                        currentPositionMs = posMs
                        if (durMs > 0L) {
                            totalDurationMs = durMs
                            isIndeterminateDuration = false
                        }

                        if (!isInf && !isIndeterminateDuration && totalDurationMs > 0L && posMs >= totalDurationMs) {
                            if (isRepeat) {
                                seekTo(0L)
                            } else {
                                pause()
                            }
                        }
                        onProgressUpdate?.invoke(currentPositionMs, totalDurationMs, isIndeterminateDuration)
                    }
                } else {
                    // Query SMIL animation state
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
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isClickable = true

        CookieManager.getInstance().setAcceptCookie(false)

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            domStorageEnabled = true
            @Suppress("DEPRECATION")
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
                val url = request.url
                // Allow internal anchor/fragment navigation on the appassets domain (e.g. #1N, #Nav, #begin)
                if (url.host.equals("appassets.androidplatform.net", ignoreCase = true)) {
                    return false
                }
                // Block all external navigation attempts
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

            hasCssAnimation = SvgAnimationHelper.containsCssAnimation(svgString)
            isInteractiveSvg = SvgAnimationHelper.containsInteractiveElements(svgString) || hasCssAnimation
            detectedViews = SvgAnimationHelper.extractViewPanels(svgString)
            currentViewIndex = if (detectedViews.isNotEmpty()) 0 else -1

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

            val htmlString = SvgAnimationHelper.wrapSvgInHtml(svgString, allowInlineScripts = true)
            currentHtmlBytes = htmlString.toByteArray(Charsets.UTF_8)

            webView.loadUrl("https://appassets.androidplatform.net/player/index.html")
        } catch (e: Throwable) {
            onError?.invoke(e)
        }
    }

    /**
     * Forwards Android KeyEvents (D-Pad, remote keys, Gamepad, keyboard) directly to the WebView DOM.
     */
    fun forwardKeyEvent(event: android.view.KeyEvent): Boolean {
        webView.requestFocus()
        return webView.dispatchKeyEvent(event)
    }

    /**
     * Sets playback speed multiplier across SMIL and CSS animations using the Web Animations API.
     */
    fun setPlaybackRate(rate: Float) {
        playbackRate = rate
        if (isPageLoaded) {
            webView.evaluateJavascript("""
                (function(r) {
                    try {
                        if (document.getAnimations) {
                            document.getAnimations().forEach(function(anim) {
                                try { anim.playbackRate = r; } catch(e) {}
                            });
                        }
                    } catch(e) {}
                })($rate);
            """.trimIndent(), null)
        }
    }

    /**
     * Navigates to a specific SVG `<view>` panel via hash navigation.
     */
    fun navigateToPanel(viewId: String) {
        if (isPageLoaded) {
            val hash = if (viewId.startsWith("#")) viewId else "#$viewId"
            webView.evaluateJavascript("location.hash = '$hash';", null)
        }
    }

    /**
     * Cycles to the next available view panel if the SVG has multiple views.
     */
    fun nextPanel(): Boolean {
        if (detectedViews.isEmpty()) return false
        currentViewIndex = (currentViewIndex + 1) % detectedViews.size
        navigateToPanel(detectedViews[currentViewIndex])
        return true
    }

    /**
     * Cycles to the previous available view panel if the SVG has multiple views.
     */
    fun prevPanel(): Boolean {
        if (detectedViews.isEmpty()) return false
        currentViewIndex = if (currentViewIndex - 1 < 0) detectedViews.size - 1 else currentViewIndex - 1
        navigateToPanel(detectedViews[currentViewIndex])
        return true
    }

    /**
     * Starts or resumes animation playback (SMIL or CSS).
     */
    fun play() {
        isPlaying = true
        if (isPageLoaded) {
            if (hasCssAnimation) {
                // Resume CSS animations via Web Animations API
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            var anims = document.getAnimations ? document.getAnimations() : [];
                            anims.forEach(function(a) { try { a.play(); } catch(e) {} });
                        } catch(e) {}
                        try {
                            var svg = document.querySelector('svg');
                            if (svg && typeof svg.unpauseAnimations === 'function') svg.unpauseAnimations();
                        } catch(e) {}
                    })();
                """.trimIndent(), null)
            } else {
                webView.evaluateJavascript("document.querySelector('svg')?.unpauseAnimations();", null)
            }
            startProgressPolling()
            if (playbackRate != 1.0f) {
                setPlaybackRate(playbackRate)
            }
        }
        onPlaybackStateChanged?.invoke(true)
    }

    /**
     * Pauses animation playback (SMIL or CSS).
     */
    fun pause() {
        isPlaying = false
        if (isPageLoaded) {
            if (hasCssAnimation) {
                // Pause CSS animations via Web Animations API
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            var anims = document.getAnimations ? document.getAnimations() : [];
                            anims.forEach(function(a) { try { a.pause(); } catch(e) {} });
                        } catch(e) {}
                        try {
                            var svg = document.querySelector('svg');
                            if (svg && typeof svg.pauseAnimations === 'function') svg.pauseAnimations();
                        } catch(e) {}
                    })();
                """.trimIndent(), null)
            } else {
                webView.evaluateJavascript("document.querySelector('svg')?.pauseAnimations();", null)
            }
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
     * Restarts playback from timestamp 0 and resets to initial view.
     */
    fun restart() {
        if (isPageLoaded) {
            if (hasCssAnimation) {
                // Reset CSS animations to beginning and navigate to #begin for interactive SVGs
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            var anims = document.getAnimations ? document.getAnimations() : [];
                            anims.forEach(function(a) { try { a.currentTime = 0; a.play(); } catch(e) {} });
                        } catch(e) {}
                        try {
                            var svg = document.querySelector('svg');
                            if (svg && typeof svg.setCurrentTime === 'function') svg.setCurrentTime(0);
                        } catch(e) {}
                        location.hash = '#begin';
                    })();
                """.trimIndent(), null)
            } else {
                webView.evaluateJavascript("""
                    (function() {
                        const svg = document.querySelector('svg');
                        if (svg && typeof svg.setCurrentTime === 'function') {
                            try { svg.setCurrentTime(0); } catch(e) {}
                        }
                        if (location.hash) {
                            location.hash = '#begin';
                        }
                    })();
                """.trimIndent(), null)
            }
        }
        currentPositionMs = 0L
        currentViewIndex = if (detectedViews.isNotEmpty()) 0 else -1
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
        if (isPageLoaded) {
            if (hasCssAnimation) {
                // Seek CSS animations via Web Animations API (currentTime is in ms)
                webView.evaluateJavascript("""
                    (function(ms) {
                        try {
                            var anims = document.getAnimations ? document.getAnimations() : [];
                            anims.forEach(function(a) {
                                try {
                                    var dur = (a.effect && a.effect.getComputedTiming) ? (a.effect.getComputedTiming().duration || 0) : 0;
                                    var targetMs = (dur > 0) ? (ms % dur) : ms;
                                    a.currentTime = targetMs;
                                } catch(e) {
                                    a.currentTime = ms;
                                }
                            });
                        } catch(e) {}
                    })($clampedPos);
                """.trimIndent(), null)
            } else {
                // SMIL seekTo uses seconds
                val sec = clampedPos / 1000f
                webView.evaluateJavascript("document.querySelector('svg')?.setCurrentTime($sec);", null)
            }
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
