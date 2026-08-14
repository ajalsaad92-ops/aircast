package com.aircast.receiver.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.aircast.receiver.core.Logger

/**
 * Injects taps and swipes so a remote controller — the receiver that is displaying
 * this phone's mirrored screen — can drive this device (point 7, reverse control).
 *
 * The user enables it once in Settings -> Accessibility. Without it, reverse control
 * silently does nothing; it is never required for plain casting. Coordinates are
 * absolute screen pixels: the caller maps the normalised pointer it received from the
 * receiver into this device's real resolution before calling in.
 */
class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Logger.i("input", "reverse-control accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not observing */ }
    override fun onInterrupt() { /* nothing to cancel */ }

    /** A single tap at absolute screen pixels. */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 50L)
    }

    /** A swipe / drag from (x1,y1) to (x2,y2) over [durationMs] (clamped 20-3000ms). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs.coerceIn(20L, 3000L))
    }

    private fun dispatch(path: Path, duration: Long) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Logger.w("input", "gesture dispatch failed: ${e.message}")
        }
    }

    companion object {
        /** Live instance while the service is enabled, else null. */
        @Volatile
        var instance: RemoteInputService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
