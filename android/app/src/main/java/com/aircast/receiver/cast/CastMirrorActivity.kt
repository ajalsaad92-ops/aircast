package com.aircast.receiver.cast

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aircast.receiver.core.Logger

/**
 * Activity that shows the WebRTC video stream coming from Quest via Cast.
 * This is launched when a Cast mirroring session starts (LAUNCH + OFFER).
 * It stays in immersive fullscreen, keeps screen on, and shows the remote video.
 */
class CastMirrorActivity : Activity() {
    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        var currentInstance: CastMirrorActivity? = null
            private set
    }

    private var root: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: "unknown"
        Logger.i("cast-mirror", "starting mirror activity for session $sessionId")
        root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(
                TextView(this@CastMirrorActivity).apply {
                    text = "Waiting for Quest stream...\nSession: $sessionId"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setPadding(32, 32, 32, 32)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root!!)
        currentInstance = this
        // Register with WebRTC manager so it can attach renderer when ready
        CastWebRtcManager.attachActivity(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
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

    fun setRendererView(view: View) {
        runOnUiThread {
            root?.removeAllViews()
            root?.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) currentInstance = null
        CastWebRtcManager.detachActivity(this)
        Logger.i("cast-mirror", "mirror activity destroyed")
    }
}
