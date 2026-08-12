package com.aircast.receiver.core

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A tiny ring buffer of protocol events. The UI's "Activity" screen reads it, which
 * makes "why did my phone not see the TV?" answerable without adb.
 */
object Logger {
    private const val MAX = 400
    private const val TAG = "AirCast"

    private val lines = ArrayDeque<String>(MAX)
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(tag: String, msg: String) = add("i", tag, msg)
    fun w(tag: String, msg: String) = add("w", tag, msg)
    fun e(tag: String, msg: String) = add("e", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        val line = "${stamp.format(Date())} [$tag] $msg"
        synchronized(lines) {
            if (lines.size >= MAX) lines.pollFirst()
            lines.addLast("$level|$line")
        }
        when (level) {
            "e" -> Log.e(TAG, "[$tag] $msg")
            "w" -> Log.w(TAG, "[$tag] $msg")
            else -> Log.i(TAG, "[$tag] $msg")
        }
        Events.emit("log", JSONObject().put("level", level).put("line", line))
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
