package com.aircast.receiver.mirror

import android.content.Context
import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Sessions
import com.aircast.receiver.player.Playback
import org.json.JSONObject
import java.util.UUID

/**
 * Serves the browser-side sender experience and the signalling endpoints behind it.
 *
 * `/` is a plain-HTTP landing page (so a QR code scanned from anywhere works), while
 * the capture page itself lives on the HTTPS port because of the secure-context rule
 * described in [TlsFactory].
 */
class MirrorHandler(private val context: Context) {

    private val prefs = Prefs.get(context)

    fun handle(req: HttpRequest): HttpResponse? = when {
        req.method == "OPTIONS" -> HttpResponse.empty(204)
        req.path == "/" || req.path == "/index.html" -> landing(req)
        req.path == "/cast" || req.path == "/sender" -> sender(req)
        req.path == "/health" -> health()
        req.path.startsWith("/mirror/") -> signaling(req)
        else -> null
    }

    // ---- pages --------------------------------------------------------------

    private fun landing(req: HttpRequest): HttpResponse {
        if (req.secure) return sender(req)
        val ip = req.localIp
        val httpsUrl = "https://$ip:${prefs.httpsPort}/cast"
        return HttpResponse.html(
            asset("landing.html")
                .replace("{{DEVICE_NAME}}", escape(prefs.deviceName))
                .replace("{{HTTPS_URL}}", httpsUrl)
                .replace("{{IP}}", ip)
                .replace("{{HTTP_PORT}}", prefs.httpPort.toString())
                .replace("{{MIRROR_ENABLED}}", if (prefs.mirrorEnabled) "1" else "0"),
        )
    }

    private fun sender(req: HttpRequest): HttpResponse {
        if (!prefs.mirrorEnabled) {
            return HttpResponse.html(
                "<!doctype html><meta charset=utf-8><body style=\"font:16px system-ui;padding:2rem\">" +
                    "Mirroring is switched off on the receiver.</body>",
                403,
            )
        }
        if (!req.secure) {
            // getDisplayMedia would be blocked here; bounce to the TLS port instead.
            val target = "https://${req.localIp}:${prefs.httpsPort}/cast"
            return HttpResponse.empty(302).also { it.headers["Location"] = target }
        }
        return HttpResponse.html(
            asset("sender.html")
                .replace("{{DEVICE_NAME}}", escape(prefs.deviceName))
                .replace("{{QUALITY}}", prefs.mirrorQuality.toString())
                .replace("{{PEER_ID}}", UUID.randomUUID().toString()),
        )
    }

    private fun health(): HttpResponse = HttpResponse.json(
        JSONObject()
            .put("ok", true)
            .put("name", prefs.deviceName)
            .put("ip", Net.primaryIp())
            .put("version", "1.0.0")
            .put("protocols", JSONObject()
                .put("dlna", prefs.dlnaEnabled)
                .put("airplay", prefs.airplayEnabled)
                .put("mirror", prefs.mirrorEnabled))
            .put("playback", Playback.toJson())
            .toString(),
    )

    // ---- signalling ---------------------------------------------------------

    private fun signaling(req: HttpRequest): HttpResponse {
        val id = req.query["id"].orEmpty()
        return when (req.path.removePrefix("/mirror/")) {
            "offer" -> {
                if (!prefs.mirrorEnabled) return HttpResponse.json("{\"error\":\"disabled\"}", 403)
                val body = JSONObject(req.bodyText())
                val peerId = body.optString("id").ifBlank { UUID.randomUUID().toString() }
                val pin = prefs.pinCode
                if (pin.isNotEmpty() && body.optString("pin") != pin) {
                    Logger.w("mirror", "rejected ${req.remoteIp}: wrong PIN")
                    return HttpResponse.json("{\"error\":\"pin\"}", 403)
                }
                MirrorSignaling.offer(
                    id = peerId,
                    ip = req.remoteIp,
                    name = body.optString("name").ifBlank { req.remoteIp },
                    sdp = body.optString("sdp"),
                )
                HttpResponse.json(JSONObject().put("id", peerId).toString())
            }

            "answer" -> {
                val peer = MirrorSignaling.peer(id) ?: return HttpResponse.json("{}", 404)
                MirrorSignaling.touch(id)
                val sdp = peer.answerSdp ?: return HttpResponse.empty(204)
                HttpResponse.json(JSONObject().put("sdp", sdp).toString())
            }

            "ice" -> when (req.method) {
                "POST" -> {
                    val body = JSONObject(req.bodyText())
                    MirrorSignaling.addSenderCandidate(id, body.getJSONObject("candidate").toString())
                    HttpResponse.json("{\"ok\":true}")
                }
                else -> {
                    val peer = MirrorSignaling.peer(id) ?: return HttpResponse.json("[]", 404)
                    MirrorSignaling.touch(id)
                    val since = req.query["since"]?.toIntOrNull() ?: 0
                    HttpResponse.json(
                        MirrorSignaling.candidatesSince(peer.receiverCandidates, since).toString(),
                    )
                }
            }

            "ping" -> {
                MirrorSignaling.touch(id)
                Sessions.touch("mirror", req.remoteIp)
                HttpResponse.json("{\"ok\":true}")
            }

            "bye" -> {
                MirrorSignaling.close(id, "sender left")
                HttpResponse.json("{\"ok\":true}")
            }

            else -> HttpResponse.notFound()
        }
    }

    // ---- assets -------------------------------------------------------------

    private val assetCache = HashMap<String, String>()

    private fun asset(name: String): String = synchronized(assetCache) {
        assetCache.getOrPut(name) {
            try {
                context.assets.open("web/$name").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Logger.e("http", "missing asset web/$name: ${e.message}")
                "<!doctype html><meta charset=utf-8><body>Asset $name missing</body>"
            }
        }
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
