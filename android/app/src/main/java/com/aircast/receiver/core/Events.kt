package com.aircast.receiver.core

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One-way channel from the native receiver stack to whoever is listening — in
 * practice the Capacitor plugin, which forwards straight into the WebView.
 *
 * Events are delivered on the main thread because Capacitor's `notifyListeners`
 * ultimately touches the WebView.
 */
object Events {
    fun interface Listener {
        fun onEvent(name: String, data: JSONObject)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun emit(name: String, data: JSONObject = JSONObject()) {
        if (listeners.isEmpty()) return
        main.post {
            for (l in listeners) {
                try {
                    l.onEvent(name, data)
                } catch (_: Exception) {
                }
            }
        }
    }
}
