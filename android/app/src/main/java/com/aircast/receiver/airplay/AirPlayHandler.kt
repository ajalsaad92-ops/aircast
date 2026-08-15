package com.aircast.receiver.airplay

import android.content.Context
import com.aircast.receiver.core.AccessGate
import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Sessions
import com.aircast.receiver.player.Playback
import java.io.File
import java.util.Locale

/**
 * The AirPlay (v1) HTTP surface: video-URL playback, photos and transport control.
 *
 * What a sender actually does: it resolves `_airplay._tcp`, asks `/server-info`, then
 * `POST /play` with either a `text/parameters` body or a binary property list holding
 * `Content-Location`. From then on it polls `/playback-info` and pushes `/rate`,
 * `/scrub` and `/stop`. All of that is plain HTTP and is implemented below.
 *
 * Mirrored from AirScreen v2.16.1 `h9/c` (the native AirPlay receiver) so that
 * Apple senders see a byte-compatible receiver: `/server-info` replies with
 * `features = 61647880183`, `model = AppleTV3,1`, `osBuildVersion = 12B435`,
 * `protovers = 1.0`, `srcvers = 211.3`, plus the plain `AirTunes/220.68` server
 * header and echoed `CSeq`. `/fp-setup` (FairPlay) is answered with 501 on purpose:
 * honouring it would mean shipping Apple's leaked mirroring keys.
 */
class AirPlayHandler(private val context: Context) {

    private val prefs = Prefs.get(context)
    private var photoFile: File? = null

    fun handle(req: HttpRequest): HttpResponse? {
        val path = req.path
        if (path == "/play" && !gatePermits(req)) return HttpResponse.empty(403)
        val handled = when (path) {
            "/server-info", "/info" -> serverInfo(req)
            "/play" -> play(req)
            "/playback-info" -> playbackInfo()
            "/rate" -> rate(req)
            "/scrub" -> scrub(req)
            "/stop" -> stop(req)
            "/photo" -> photo(req)
            "/slideshow-features" -> HttpResponse.xml(plist("<dict/>"))
            "/authorize" -> HttpResponse.empty(200)
            "/reverse" -> reverse(req)
            "/fp-setup", "/pair-setup", "/pair-verify", "/stream" -> unsupported(path)
            "/getProperty" -> HttpResponse.xml(plist("<dict/>"))
            "/setProperty" -> HttpResponse.empty(200)
            "/action" -> HttpResponse.empty(200)
            else -> null
        }
        if (handled != null && path != "/playback-info" && path != "/scrub") {
            Sessions.touch("airplay", req.remoteIp, senderName(req))
        }
        return handled
    }

    /**
     * Access control for `/play`. When a security mode is configured the sender
     * must include the shared secret in the `X-AirCast-Code` header — the
     * on-screen prompt adds exactly that header before repeating the request.
     * Stock iOS senders cannot know about the header, so unmodified Apple
     * clients are only gated when the mode is `off` (legacy behaviour).
     */
    private fun gatePermits(req: HttpRequest): Boolean {
        val secret = req.header("x-aircast-code")
        if (AccessGate.airplayAuthorized(secret)) return true
        Logger.w("airplay", "/play blocked for ${req.remoteIp}: ${prefs.airplaySecurityMode} gate failed")
        return false
    }

    private fun senderName(req: HttpRequest): String =
        req.header("x-apple-device-id")?.let { "Apple device" }
            ?: req.header("user-agent")?.substringBefore('/')?.takeIf { it.isNotBlank() }
            ?: ""

    // ---- discovery ----------------------------------------------------------

    private fun serverInfo(req: HttpRequest): HttpResponse {
        Logger.i("airplay", "server-info requested by ${req.remoteIp}")
        // Verbatim mirror of AirScreen v2.16.1 h9/c serverInfo plist values.
        val body = """<dict>
<key>features</key>
<integer>61647880183</integer>
<key>macAddress</key>
<string>${Net.deviceIdColon(context).replace(":", "")}</string>
<key>model</key>
<string>AppleTV3,1</string>
<key>osBuildVersion</key>
<string>12B435</string>
<key>protovers</key>
<string>1.0</string>
<key>srcvers</key>
<string>211.3</string>
<key>vv</key>
<integer>2</integer>
<key>deviceid</key>
<string>${Net.deviceIdColon(context)}</string>
</dict>"""
        return HttpResponse.xml(plist(body))
    }

    // ---- playback -----------------------------------------------------------

    private fun play(req: HttpRequest): HttpResponse {
        val contentType = req.header("content-type").orEmpty().lowercase(Locale.US)
        val parsed = if (contentType.contains("parameters")) {
            parseTextParameters(req.bodyText())
        } else {
            parseBinaryPlistLoosely(req.bodyLatin1())
        }

        val url = parsed.first
        if (url.isNullOrBlank()) {
            Logger.w("airplay", "play request without a usable Content-Location")
            return HttpResponse.text("Bad Request", 400)
        }
        val startFraction = parsed.second

        Logger.i("airplay", "play $url from ${req.remoteIp}")
        Playback.metadata = ""
        Playback.open(
            context,
            Playback.Request(
                url = url,
                kind = com.aircast.receiver.dlna.Soap.guessKind("", url),
                title = url.substringAfterLast('/').substringBefore('?'),
                // AirPlay sends a *fraction* of the duration when < 1, else absolute seconds.
                startPositionMs = if (startFraction in 0.0..1.0) 0 else (startFraction * 1000).toLong(),
                source = "airplay",
                senderName = senderName(req),
                senderIp = req.remoteIp,
                subtitleUrl = parsed.third.orEmpty(),
            ),
        )
        return HttpResponse.empty(200)
    }

    private fun playbackInfo(): HttpResponse {
        val durationSec = Playback.durationMs() / 1000.0
        val positionSec = Playback.positionMs() / 1000.0
        val playing = Playback.state == Playback.State.PLAYING
        val ready = Playback.state != Playback.State.NO_MEDIA
        val body = """<dict>
  <key>duration</key><real>$durationSec</real>
  <key>position</key><real>$positionSec</real>
  <key>rate</key><real>${if (playing) 1.0 else 0.0}</real>
  <key>readyToPlay</key><${if (ready) "true" else "false"}/>
  <key>playbackBufferEmpty</key><${if (ready) "false" else "true"}/>
  <key>playbackBufferFull</key><false/>
  <key>playbackLikelyToKeepUp</key><true/>
  <key>loadedTimeRanges</key>
  <array><dict><key>duration</key><real>$durationSec</real><key>start</key><real>0.0</real></dict></array>
  <key>seekableTimeRanges</key>
  <array><dict><key>duration</key><real>$durationSec</real><key>start</key><real>0.0</real></dict></array>
</dict>"""
        return HttpResponse.xml(plist(body))
    }

    private fun rate(req: HttpRequest): HttpResponse {
        val value = req.query["value"]?.toDoubleOrNull() ?: 1.0
        if (value < 0.5) Playback.controller?.pause() else Playback.controller?.play()
        return HttpResponse.empty(200)
    }

    private fun scrub(req: HttpRequest): HttpResponse {
        if (req.method == "GET") {
            val body = "duration: ${Playback.durationMs() / 1000.0}\n" +
                "position: ${Playback.positionMs() / 1000.0}\n"
            return HttpResponse(200, "text/parameters", body.toByteArray(Charsets.UTF_8))
        }
        val position = req.query["position"]?.toDoubleOrNull()
        if (position != null) Playback.controller?.seekTo((position * 1000).toLong())
        return HttpResponse.empty(200)
    }

    private fun stop(req: HttpRequest): HttpResponse {
        Logger.i("airplay", "stop from ${req.remoteIp}")
        Playback.controller?.stopPlayback() ?: Playback.setState(Playback.State.STOPPED)
        Sessions.end("airplay", req.remoteIp)
        return HttpResponse.empty(200)
    }

    /**
     * Photos arrive as a raw JPEG body on `PUT /photo`. Writing it to cache and handing
     * the player a `file://` URL keeps one rendering path for every media kind.
     */
    private fun photo(req: HttpRequest): HttpResponse {
        if (req.method != "PUT" && req.method != "POST") return HttpResponse.text("Method Not Allowed", 405)
        if (req.body.isEmpty()) return HttpResponse.empty(200)
        return try {
            val dir = File(context.cacheDir, "airplay").apply { mkdirs() }
            val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            file.writeBytes(req.body)
            photoFile?.delete()
            photoFile = file
            Logger.i("airplay", "photo received (${req.body.size / 1024} KB) from ${req.remoteIp}")
            Playback.open(
                context,
                Playback.Request(
                    url = file.toURI().toString(),
                    kind = Playback.Kind.IMAGE,
                    title = "",
                    source = "airplay",
                    senderName = senderName(req),
                    senderIp = req.remoteIp,
                ),
            )
            HttpResponse.empty(200)
        } catch (e: Exception) {
            Logger.e("airplay", "photo failed: ${e.message}")
            HttpResponse.text("Internal Server Error", 500)
        }
    }

    /** The sender keeps this socket open to receive events; we accept and hold it. */
    private fun reverse(req: HttpRequest): HttpResponse {
        Logger.i("airplay", "reverse channel opened by ${req.remoteIp}")
        return HttpResponse(
            status = 101,
            contentType = null,
            headers = linkedMapOf(
                "Upgrade" to "PTTH/1.0",
                "Connection" to "Upgrade",
            ),
            hijack = true,
        )
    }

    private fun unsupported(path: String): HttpResponse {
        Logger.w("airplay", "$path requested — mirroring/pairing is not implemented (see README)")
        return HttpResponse.text("Not Implemented", 501)
    }

    // ---- body parsing -------------------------------------------------------

    /** `Content-Location: http://…\nStart-Position: 0.0\n` */
    private fun parseTextParameters(body: String): Triple<String?, Double, String?> {
        var url: String? = null
        var start = 0.0
        var subtitle: String? = null
        for (line in body.split('\n')) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim().lowercase(Locale.US)
            val value = line.substring(i + 1).trim()
            when (key) {
                "content-location" -> url = value
                "start-position" -> start = value.toDoubleOrNull() ?: 0.0
                // AirPin-style helper: senders that cannot attach subtitles directly
                // can pass `subtitle: /subtitle/<token>.vtt` alongside the URL.
                "subtitle" -> subtitle = value
            }
        }
        return Triple(url, start, subtitle)
    }

    /**
     * Binary property lists are only used to carry a handful of scalars here, so instead
     * of a full bplist reader we pull the first absolute URL out of the payload. Both the
     * key name and the value are stored as plain ASCII runs, which makes this reliable
     * for `/play` while staying ~20 lines instead of ~400.
     */
    private fun parseBinaryPlistLoosely(latin1: String): Triple<String?, Double, String?> {
        val url = Regex("https?://[^\\u0000-\\u0020\"'<>\\\\^`{|}]+")
            .find(latin1)?.value
            ?.trimEnd('.', ',', ';')
        val start = Regex("Start-Position:?\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
            .find(latin1)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val subtitle = Regex("Subtitle:?\\s*(/\\S+)", RegexOption.IGNORE_CASE)
            .find(latin1)?.groupValues?.get(1)
        return Triple(url, start, subtitle)
    }

    // ---- plist helpers ------------------------------------------------------

    private fun plist(inner: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
$inner
</plist>
"""

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
