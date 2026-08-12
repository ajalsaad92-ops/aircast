package com.aircast.receiver.cast

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.aircast.receiver.core.Logger

/**
 * A full-screen browser with nothing around it.
 *
 * This exists for one concrete case: a Meta Quest headset can cast to a *web page*
 * (`oculus.com/casting`) but not to any receiver an ordinary app is allowed to
 * implement — Google Cast reception needs a Google-issued device certificate, and
 * Quest does not speak Miracast, AirPlay or DLNA at all. Opening that page here, on
 * the TV, is therefore the whole feature: the headset streams, this window shows it,
 * and the user never leaves AirCast.
 *
 * It is deliberately a plain [Activity] with a hand-built WebView rather than the
 * Capacitor bridge: the bridge WebView carries the app's own JavaScript context and
 * plugin surface, none of which should be reachable from a remote page.
 */
class CastWebActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL

        webView = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // The casting page checks the UA and refuses to serve the player to
                // anything it reads as a mobile browser.
                userAgentString = DESKTOP_UA
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    Logger.i("castweb", "loaded ${url?.take(60)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                // The page asks for camera/microphone when negotiating the stream; on a
                // TV box there is nothing to leak and denying it kills the cast.
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )

        webView.loadUrl(url)
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

    /** Back steps through page history first, so a wrong tap does not drop the cast. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try {
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL = "https://www.oculus.com/casting"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}
