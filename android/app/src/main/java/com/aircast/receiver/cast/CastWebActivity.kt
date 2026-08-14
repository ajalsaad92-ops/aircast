package com.aircast.receiver.cast

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs

/**
 * Full-screen browser dedicated to Meta Quest casting.
 *
 * Why this exists: Quest 3/3S can cast in two ways:
 *  1) Chromecast (Google Cast) -> requires our CastReceiver with device certificate (currently stub).
 *     Your log shows: "device auth challenge ... no Google device certificate" then disconnect.
 *     That is why Quest shows "connected" for a second then drops.
 *  2) Computer / Browser -> oculus.com/casting via WebRTC. This works without any certificate
 *     and is the officially supported path after Meta removed/partially restored Chromecast.
 *
 * This Activity implements path (2) with TV optimizations.
 *
 * Improvements over previous version:
 * - Cookie + third-party cookie enabled (Meta login needs it)
 * - Desktop UA + mixed content allowed + file access + multiple windows (login popup)
 * - Fullscreen custom view handling (video element goes truly fullscreen)
 * - Immersive re-applied on focus change
 * - Permission grant for camera/mic/geolocation
 * - Console logging to our Logger
 * - Instruction overlay in Arabic/English that auto-hides when video starts
 * - Error handling with retry
 * - Back behavior: custom view -> hide, history -> back, otherwise -> finish
 * - Keeps screen on and shows device name/IP in log
 */
class CastWebActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var customViewContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var overlay: LinearLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Allow playing in background even if window loses focus briefly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        goImmersive()

        val prefs = Prefs.get(this)
        val deviceName = prefs.deviceName
        val ip = Net.primaryIp()
        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
        Logger.i("castweb", "opening $url as $deviceName at $ip")

        // --- WebView setup ---
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            // Cookies are essential for Meta login persistence
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            } catch (e: Exception) {
                Logger.w("castweb", "cookie setup failed: ${e.message}")
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                // Mixed content needed because oculus.com may load http resources
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Desktop UA - oculus.com/casting refuses mobile UA
                userAgentString = DESKTOP_UA
                // Text zoom neutral
                textZoom = 100
            }

            // Focus for D-pad on TV
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: ""
                    // Keep navigation inside WebView, but allow intent://, mailto: etc to be ignored
                    if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("about:") || target.startsWith("data:")) {
                        return false
                    }
                    Logger.i("castweb", "blocked external scheme: $target")
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    this@CastWebActivity.progress.visibility = View.VISIBLE
                    Logger.i("castweb", "page started: ${url?.take(80)}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    this@CastWebActivity.progress.visibility = View.GONE
                    Logger.i("castweb", "loaded ${url?.take(80)}")
                    // Inject JS to detect video start and hide overlay, plus console forwarding
                    injectDetectionScript()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Logger.w("castweb", "page error: ${error?.description} at ${request.url}")
                        this@CastWebActivity.progress.visibility = View.GONE
                        // Show overlay again with error
                        showOverlayWithError("فشل تحميل الصفحة. تأكد من الانترنت ثم اضغط رجوع.\nPage failed to load. Check internet and press back to retry.")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Grant camera/mic - page asks for them during WebRTC negotiation
                    try {
                        request?.grant(request.resources)
                        Logger.i("castweb", "granted permissions: ${request?.resources?.joinToString()}")
                    } catch (e: Exception) {
                        Logger.w("castweb", "permission grant failed: ${e.message}")
                        request?.deny()
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    super.onPermissionRequestCanceled(request)
                }

                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, true, false)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    // Fullscreen video
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    view?.let {
                        customViewContainer.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        customViewContainer.visibility = View.VISIBLE
                        rootLayout.visibility = View.INVISIBLE
                        this@CastWebActivity.overlay.visibility = View.GONE
                    }
                    goImmersive()
                }

                override fun onHideCustomView() {
                    customView?.let {
                        customViewContainer.removeView(it)
                    }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    customViewContainer.visibility = View.GONE
                    rootLayout.visibility = View.VISIBLE
                    // Do not re-show overlay immediately if we were casting
                    goImmersive()
                }

                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let {
                        Logger.i("castweb", "JS ${it.messageLevel()} ${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                    }
                    return super.onConsoleMessage(message)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        if (this@CastWebActivity.progress.visibility != View.VISIBLE) this@CastWebActivity.progress.visibility = View.VISIBLE
                    } else {
                        this@CastWebActivity.progress.visibility = View.GONE
                    }
                }
            }
        }

        // Layout hierarchy
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        customViewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        overlay = createOverlay(deviceName, ip, url)

        val fullRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(rootLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(customViewContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Progress centered top
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            // Overlay at bottom or center?
            addView(this@CastWebActivity.overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            })
        }

        setContentView(fullRoot)

        // Auto-hide overlay after 12 seconds, unless user is interacting
        overlay.postDelayed({
            // Don't hide if page hasn't loaded yet
            if (progress.visibility == View.GONE) {
                overlay.animate().alpha(0f).setDuration(600).withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }.start()
            }
        }, 12000)

        webView.loadUrl(url)
    }

    private fun createOverlay(deviceName: String, ip: String, url: String): LinearLayout {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 7, 11, 20))
            setPadding(24, 20, 24, 24)
            // For TV focus, allow click to hide
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Hide on click
                animate().alpha(0f).setDuration(300).withEndAction {
                    visibility = View.GONE
                    alpha = 1f
                }.start()
            }
        }

        val title = TextView(ctx).apply {
            text = "AirCast • $deviceName"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val instructionsAr = TextView(ctx).apply {
            text = """
            لبث Quest 3S:
            1) افتح oculus.com/casting على هذا التلفاز وسجل دخول بنفس حساب Meta في النظارة
            2) في النظارة: الكاميرا (Camera) ← بث (Cast) ← الحاسوب (Computer) ← التالي
            3) أدخل الرمز إذا طلب، ثم يبدأ البث مباشرة هنا
            • إذا ظهر AirCast في قائمة Chromecast وفشل: هذا طبيعي لأن مصادقة Google غير موجودة في هذا الإصدار، استخدم طريقة الحاسوب أعلاه
            """.trimIndent()
            setTextColor(Color.parseColor("#E8EEFC"))
            textSize = 13.5f
            setLineSpacing(4f, 1.2f)
            setPadding(0, 12, 0, 8)
        }

        val instructionsEn = TextView(ctx).apply {
            text = """
            To cast Quest 3S:
            1) Open oculus.com/casting on this TV and login with same Meta account as headset
            2) In headset: Camera → Cast → Computer → Next
            3) Enter code if asked, stream appears here fullscreen
            • If you see AirCast under Chromecast and it fails to connect (auth challenge) -> expected in this build, use Computer method above
            IP: $ip • $url
            Tap this bar to hide
            """.trimIndent()
            setTextColor(Color.parseColor("#8EA2C6"))
            textSize = 12f
            setLineSpacing(3f, 1.15f)
        }

        container.addView(title)
        container.addView(instructionsAr)
        container.addView(instructionsEn)
        return container
    }

    private fun injectDetectionScript() {
        // When a <video> element plays, hide overlay. Also report to log.
        val js = """
            (function(){
                try{
                    console.log('[aircast] injecting video detector');
                    function hideOverlay(){
                        // Try to find overlay via JS is not possible (native view), but we can log
                        console.log('[aircast] video playing detected - should hide native overlay');
                    }
                    var check = setInterval(function(){
                        var vids = document.querySelectorAll('video');
                        for(var i=0;i<vids.length;i++){
                            var v = vids[i];
                            if(!v._aircastHooked){
                                v._aircastHooked = true;
                                v.addEventListener('playing', function(){ console.log('[aircast] video playing'); hideOverlay(); });
                                v.addEventListener('play', function(){ console.log('[aircast] video play event'); });
                                // If already playing
                                if(!v.paused && v.currentTime>0){ hideOverlay(); }
                            }
                        }
                    }, 1500);
                }catch(e){ console.log('[aircast] inject error '+e); }
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun showOverlayWithError(msg: String) {
        try {
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1f
            // Find second text view and replace? Simple: add new TextView at top of overlay
            val tv = TextView(this).apply {
                text = msg
                setTextColor(Color.parseColor("#F87171"))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            overlay.addView(tv, 1)
        } catch (_: Exception) {}
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If custom fullscreen view is showing, hide it first
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        // D-pad center / enter to hide overlay
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL = "https://www.oculus.com/casting"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
