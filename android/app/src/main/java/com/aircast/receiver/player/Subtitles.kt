package com.aircast.receiver.player

import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.Logger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom subtitle upload + serving. The web UI posts the subtitle text directly and the
 * receiver converts SRT/ASS/VTT into browser-safe WebVTT, then serves it under
 * `/subtitle/$token.vtt`. Tokens are single-use-style random strings kept in memory.
 */
object Subtitles {

    private val store = ConcurrentHashMap<String, String>()
    private val files = ConcurrentHashMap<String, File>()
    private const val MAX_CHARS = 2 * 1024 * 1024 // 2 MB cap

    /** POST /subtitle with body `{"text": "...", "format": "srt"|"vtt"|"ass"}`. */
    fun handle(req: HttpRequest): HttpResponse? {
        if (!req.path.startsWith("/subtitle")) return null

        if (req.method.equals("POST", ignoreCase = true)) {
            val token = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            val body = req.bodyText()
            if (body.length > MAX_CHARS) {
                return HttpResponse.json(JSONObject().put("error", "subtitle too large").toString(), 413)
            }
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return HttpResponse.json(JSONObject().put("error", "invalid json").toString(), 400)
            }
            val text = json.optString("text")
            val format = json.optString("format", "srt")
            val vtt = when (format) {
                "vtt" -> ensureWebVttHeader(text)
                "ass" -> convertAss(text)
                else -> convertSrt(text)
            }
            store[token] = vtt
            val res = JSONObject()
                .put("token", token)
                .put("url", "/subtitle/$token.vtt")
                .put("cues", countCues(vtt))
            return HttpResponse.json(res.toString())
        }

        // GET /subtitle/$token.vtt
        val token = req.path.substringAfter("/subtitle/").substringBefore(".vtt")
        val vtt = store[token] ?: return HttpResponse.notFound()
        return HttpResponse(
            status = 200,
            contentType = "text/vtt; charset=utf-8",
            body = vtt.toByteArray(Charsets.UTF_8),
            headers = mutableMapOf("Access-Control-Allow-Origin" to "*"),
        )
    }

    private fun ensureWebVttHeader(text: String): String {
        val t = text.trim()
        return if (t.startsWith("WEBVTT")) t
        else "WEBVTT\n\n$t"
    }

    /** Minimal SRT -> WebVTT conversion (timecodes are identical). */
    private fun convertSrt(text: String): String {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = StringBuilder("WEBVTT\n\n")
        var timecodes = 0
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) { out.append("\n"); continue }
            // SRT time line: 00:00:01,500 --> 00:00:04,000
            val m = t.matchResult("""(\d\d):(\d\d):(\d\d),(\d\d\d) ?--> ?(\d\d):(\d\d):(\d\d),(\d\d\d)""")
            if (m) {
                val fixed = t.replace(",", ".")
                out.append(fixed).append("\n"); timecodes++
            } else if (t.toIntOrNull() != null) {
                continue // sequence numbers are dropped
            } else {
                out.append(t).append("\n")
            }
        }
        return out.toString()
    }

    /** Very small ASS -> WebVTT conversion: drops the header and restyles \N line breaks. */
    private fun convertAss(text: String): String {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = StringBuilder("WEBVTT\n\n")
        var inEvents = false
        for (line in lines) {
            if (line.startsWith("[Events]")) { inEvents = true; continue }
            if (!inEvents || line.startsWith("Format:") || line.startsWith("Dialogue:").not() && line.startsWith("[") ) continue
            if (!line.startsWith("Dialogue:")) continue
            // Dialogue: 0,0:00:01.00,0:00:04.00,...
            val parts = line.substringAfter("Dialogue:").split(",", limit = 10)
            if (parts.size < 10) continue
            val start = fixAssTime(parts[1])
            val end = fixAssTime(parts[2])
            // skip style/parts between end and text (indices 3..8), text is index 9
            val rawText = parts.drop(9).joinToString(",").replace("\\N", "\n")
            out.append("$start --> $end\n$rawText\n\n")
        }
        return out.toString()
    }

    private fun fixAssTime(s: String): String {
        // 0:00:01.00 -> 00:00:01.000
        val parts = s.split(":")
        return buildString {
            if (parts[0].length < 2) append("0")
            append(parts[0]).append(":").append(parts[1]).append(":")
            val sec = parts.getOrNull(2) ?: "0"
            val dot = sec.indexOf('.')
            append(if (dot < 0) "$sec.000" else sec.substring(0, dot) + "." + sec.substring(dot + 1).padEnd(3, '0'))
        }
    }

    private fun String.matchResult(pattern: String): Boolean =
        Regex(pattern).matches(this)

    private fun countCues(vtt: String): Int =
        Regex("""\d\d:\d\d:\d\d""").findAll(vtt).count() / 2
}
