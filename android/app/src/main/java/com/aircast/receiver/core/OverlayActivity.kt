package com.aircast.receiver.core

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.aircast.receiver.R
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Net
import com.aircast.receiver.service.ReceiverService
import android.os.Handler
import android.os.Looper
import java.util.Random

/**
 * A transparent full-window overlay drawn on top of the home screen (and over other apps)
 * while the receiver is on and no player is showing — AirScreen's "screensaver canvas"
 * behaviour. Slowly drifting particles plus the device name prove the receiver is alive
 * and give the user an obvious place to return to.
 *
 * Tapping it brings the app back to the foreground.
 */
class OverlayActivity : Activity() {

    private lateinit var canvas: OverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceName = try {
            Prefs.get(this).deviceName?.takeIf { it.isNotBlank() } ?: Net.defaultDeviceName()
        } catch (_: Exception) {
            Net.defaultDeviceName()
        }

        canvas = OverlayView(this, deviceName) { bringAppToFront() }
        setContentView(canvas)

        window.attributes = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // The launcher activity hides this activity before it launches (see onNewIntent),
        // so nothing shows when the user opens the app normally.
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // The system's ActivityManager launches overlays through the launcher activity;
        // keep the overlay only while the receiver genuinely needs it.
        if (intent?.action == ACTION_HIDE) finish()
    }

    override fun onResume() {
        super.onResume()
        // Stop if the service is no longer running or the mode was switched off.
        if (ReceiverService.instance == null || Prefs.get(this).backgroundMode == "off") {
            finish()
        }
    }

    private fun bringAppToFront() {
        try {
            val pm = packageManager
            val launch = pm.getLaunchIntentForPackage(packageName) ?: return
            launch.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(launch)
        } catch (_: Exception) {
            /* ignore */
        }
    }

    override fun onDestroy() {
        canvas.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "com.aircast.receiver.OVERLAY_HIDE"
    }
}

/**
 * A lightweight custom-drawn canvas: a dark translucent backdrop, slow floating dots and
 * the receiver name underlined by the brand accent. Nothing here allocates per frame
 * beyond the paint reused on every draw, so it costs ~0 CPU when idle.
 */
private class OverlayView(
    private val activity: Activity,
    private val deviceName: String,
    private val onTapped: () -> Unit,
) : View(activity) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val namePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9aa3b2")
        textSize = 30f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val accent = Color.parseColor("#7c5cff")

    private data class Dot(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float)

    private val random = Random()
    private val dots = Array(26) {
        Dot(random.nextFloat(), random.nextFloat(), (random.nextFloat() - 0.5f) * 0.0002f,
            (random.nextFloat() - 0.5f) * 0.0002f, 3f + random.nextFloat() * 6f)
    }

    private val handler = Handler(Looper.getMainLooper())

    private val tick: Runnable = object : Runnable {
        override fun run() {
            dots.forEach { dot ->
                dot.x += dot.vx
                dot.y += dot.vy
                if (dot.x < 0f || dot.x > 1f) dot.vx = -dot.vx
                if (dot.y < 0f || dot.y > 1f) dot.vy = -dot.vy
                dot.x = dot.x.coerceIn(0f, 1f)
                dot.y = dot.y.coerceIn(0f, 1f)
            }
            invalidate()
            handler.postDelayed(this, 16)
        }
    }

    init {
        setOnClickListener { onTapped() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()

        // Backdrop: deep blue gradient, slightly transparent.
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#0d1226"), Color.parseColor("#1a1133"),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)

        // Drifting dots.
        dots.forEach { dot ->
            paint.shader = null
            paint.color = accent
            paint.alpha = 220
            c.drawCircle(dot.x * w, dot.y * h, dot.r, paint)
        }

        // Receiver name, centred in the lower third.
        paint.color = accent
        c.drawText(deviceName, w / 2f, h - 180f, namePaint)
        paint.color = Color.parseColor("#9aa3b2")
        c.drawText(activity.getString(R.string.overlay_subtitle), w / 2f, h - 110f, subPaint)
    }
}
