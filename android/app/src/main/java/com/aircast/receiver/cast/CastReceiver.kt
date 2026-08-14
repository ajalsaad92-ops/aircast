package com.aircast.receiver.cast

import android.content.Context
import com.aircast.receiver.core.AccessGate
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Sessions
import com.aircast.receiver.mirror.TlsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The control half of a Google Cast receiver - now with full device auth (replay) and WebRTC mirroring.
 *
 * Scope, stated plainly: this speaks enough CASTV2 for a sender to *discover* AirCast,
 * open a connection, keep it alive and launch a session. It does **not** yet carry the
 * mirrored picture — that is the `urn:x-cast:com.google.cast.webrtc` OFFER/ANSWER plus a
 * Cast-flavoured RTP stream, and it is a substantially bigger piece of work.
 * For private APK, we embed one valid tuple from Shanocast (peer key + cert + signature for 26 Sep 2023)
 * and use it with Bypass Device Auth enabled on Quest sender. Without bypass, you need the full
 * 795-signature table and to generate peer_certificate for today (index = (now - 1692057600)/2days).
 *
 * It is built in this order deliberately. The genuinely unknown question is whether a
 * sender will talk to a receiver holding no Google-issued device certificate; every
 * remaining step is known-but-laborious. Getting to "AirCast appears in the list and a
 * session opens" answers that question for the price of one build, so the log lines
 * below are the real deliverable of this stage.
 * Flow after auth:
 *  - GET_STATUS / LAUNCH -> create session
 *  - NS_WEBRTC OFFER -> create PeerConnection, answer, exchange ICE
 *  - Video track -> CastMirrorActivity via CastWebRtcManager
 */
class CastReceiver(private val context: Context) {

    private val prefs = Prefs.get(context)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Set once a sender launches an app, so a second sender sees the receiver as busy. */
    @Volatile private var sessionId: String? = null

    /** Friendly name of the current socket, read from the first CONNECT payload. */
    @Volatile private var currentSenderName: String = ""
    @Volatile private var currentAppId: String = ""
    @Volatile private var currentPeer: String = ""
    @Volatile private var currentOutput: DataOutputStream? = null
    @Volatile private var currentSourceId: String = "receiver-0"
    @Volatile private var currentDestId: String = "sender-0"

    fun start() {
        if (running.getAndSet(true)) return
        // Try to use Cast-specific TLS with embedded peer cert, fallback to generic self-signed
        val factory = CastAuth.createTlsSocketFactory() ?: TlsFactory.serverSocketFactory(context)
        if (factory == null) {
            running.set(false)
            Logger.e("cast", "no TLS available; Cast receiver not started")
            return
        }
        try {
            val socket = factory.createServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(CastV2.PORT), 8)
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "cast-accept").apply { isDaemon = true }.start()
            Logger.i("cast", "Cast control channel listening on ${CastV2.PORT} (with ${if (factory === CastAuth.createTlsSocketFactory()) "embedded peer cert" else "generic cert"})")
            // Init WebRTC manager callbacks
            CastWebRtcManager.init(context)
            CastWebRtcManager.sendAnswer = { sdp -> sendWebRtcAnswer(sdp) }
            CastWebRtcManager.sendIce = { candidate, mid, index -> sendWebRtcIce(candidate, mid, index) }
        } catch (e: Exception) {
            running.set(false)
            Logger.e("cast", "port ${CastV2.PORT} unavailable: ${e.message}")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sessionId = null
        currentOutput = null
        pool.shutdownNow()
        CastWebRtcManager.stop()
        Logger.i("cast", "Cast control channel stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) Logger.w("cast", "accept failed: ${e.message}")
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun serve(socket: Socket) {
        val peer = socket.inetAddress?.hostAddress ?: "?"
        Logger.i("cast", "sender connected from $peer")

        // ---- Cast security gate (AirScreen `Cast security`): new senders may be
        //      held here until the user accepts or rejects them in the UI.
        if (!AccessGate.castShouldProceed(peer, peer, currentSenderName)) {
            // Wait up to the gate timeout for a user decision before dropping.
            val deadline = System.currentTimeMillis() + 60_000L
            var granted = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (Prefs.get(context).castTrustedPeers().contains(peer) ||
                    !AccessGate.isPending(peer)
                ) {
                    granted = true
                    break
                }
            }
            if (!granted) {
                Logger.i("cast", "sender $peer removed — not accepted in time")
                Sessions.end("cast", peer)
                try { socket.close() } catch (_: Exception) {}
                return
            }
            Logger.i("cast", "sender $peer accepted through the security gate")
        }

        Sessions.touch("cast", peer)
        var output: DataOutputStream? = null
        try {
            socket.tcpNoDelay = true
            // Senders idle between heartbeats; the timeout only needs to outlive one.
            socket.soTimeout = 60_000
            val input = DataInputStream(socket.getInputStream().buffered())
            output = DataOutputStream(socket.getOutputStream())
            currentOutput = output
            currentPeer = peer
            while (running.get() && !socket.isClosed) {
                val message = CastV2.read(input) ?: break
                handle(message, output, peer)
            }
        } catch (e: Exception) {
            Logger.w("cast", "session with $peer ended: ${e.message}")
        } finally {
            Sessions.end("cast", peer)
            if (currentPeer == peer) {
                currentOutput = null
                currentPeer = ""
                currentSenderName = ""
            }
            try { socket.close() } catch (_: Exception) {}
            Logger.i("cast", "sender $peer disconnected")
        }
    }

    private fun handle(message: CastV2.Message, out: DataOutputStream, peer: String) {
        // Remember source/dest for replies
        currentSourceId = message.destinationId.ifEmpty { "receiver-0" }
        currentDestId = message.sourceId.ifEmpty { "sender-0" }
        when (message.namespace) {
            CastV2.NS_CONNECTION -> {
                val type = jsonType(message.payloadUtf8)
                Logger.i("cast", "connection: $type from $peer")
                if (type == "CONNECT") {
                    // `userAgent` in CONNECT is the sender app; the friendly device
                    // name is best-effort and shows up in the accept/reject dialog.
                    val payload = JSONObject(message.payloadUtf8 ?: "{}")
                    val name = payload.optString("userAgent", "")
                        .ifEmpty { payload.optString("displayName", "") }
                    currentSenderName = name
                    AccessGate.updatePendingName(peer, name)
                }
                // CONNECT needs no reply; CLOSE means this virtual connection is done.
            }

            CastV2.NS_HEARTBEAT -> {
                if (jsonType(message.payloadUtf8) == "PING") {
                    reply(out, message, CastV2.NS_HEARTBEAT, JSONObject().put("type", "PONG"))
                }
            }

            CastV2.NS_DEVICEAUTH -> {
                // The moment of truth. A real Chromecast answers with a certificate chain
                // rooted in Google's Cast CA and a signature over the sender's nonce; we
                // have neither. Logging the challenge and staying silent is the honest
                // move — if the sender proceeds anyway, unattested receivers are viable
                // and the rest of the protocol is worth building. If it hangs up here,
                // that is the wall, and no amount of further work moves it.
                Logger.w(
                    "cast",
                    "device auth challenge from $peer (${message.payloadBinary?.size ?: 0} bytes) - no Google device certificate. " +
                        "Quest Chromecast mode will fail here (expected). Use Camera -> Cast -> Computer via oculus.com/casting screen instead. " +
                        "For full Cast: need replay certs (Shanocast method) or enable Bypass Device Auth on sender via adb.",
                )
                handleDeviceAuth(message, out, peer)
            }

            CastV2.NS_RECEIVER -> handleReceiver(message, out, peer)

            CastV2.NS_WEBRTC -> {
                // Arriving here at all would mean discovery, auth and launch all passed.
                Logger.w("cast", "mirroring offer received - streaming layer not implemented yet")
                handleWebRtc(message, out, peer)
            }

            else -> Logger.i("cast", "unhandled namespace ${message.namespace} payload=${message.payloadUtf8?.take(200)}")
        }
    }

    private fun handleDeviceAuth(message: CastV2.Message, out: DataOutputStream, peer: String) {
        val payloadBytes = message.payloadBinary
        if (payloadBytes != null) {
            // Try to decode AuthChallenge to log nonce
            try {
                val challenge = parseAuthChallenge(payloadBytes)
                Logger.i("cast", "device auth challenge from $peer nonce=${challenge?.take(10)}... (${payloadBytes.size} bytes)")
            } catch (_: Exception) {}
        }
        Logger.i("cast", "device auth challenge from $peer (${payloadBytes?.size ?: 0} bytes) - responding with embedded cert")
        // Build AuthResponse using embedded certs
        val responseBytes = CastAuth.buildAuthResponse()
        if (responseBytes.isEmpty()) {
            Logger.w("cast", "failed to build auth response, sending empty to allow bypass mode to proceed")
            return
        }
        try {
            // DeviceAuthMessage with response field
            val msg = CastV2.Message(
                sourceId = message.destinationId.ifEmpty { "receiver-0" },
                destinationId = message.sourceId.ifEmpty { "sender-0" },
                namespace = CastV2.NS_DEVICEAUTH,
                payloadType = CastV2.PAYLOAD_BINARY,
                payloadBinary = responseBytes
            )
            CastV2.write(out, msg)
            Logger.i("cast", "sent device auth response to $peer (${responseBytes.size} bytes) - if Quest has Bypass enabled, it will proceed")
        } catch (e: Exception) {
            Logger.e("cast", "auth response failed: ${e.message}")
        }
    }

    // Very minimal protobuf parser for AuthChallenge: field 2 = sender_nonce bytes
    private fun parseAuthChallenge(data: ByteArray): String? {
        // DeviceAuthMessage wraps AuthChallenge in field 1, which wraps sender_nonce in field 2
        // For logging we just try to find nonce as field 2 bytes
        var i = 0
        while (i < data.size) {
            val key = data[i].toInt() and 0xFF
            val field = key shr 3
            val wire = key and 0x7
            i++
            if (wire == 2) {
                // length-delimited
                if (i >= data.size) break
                val len = data[i].toInt() and 0xFF
                // This is simplified - assumes varint 1 byte for short lengths
                i++
                if (field == 2 && len > 0 && len < 64) {
                    val end = (i + len).coerceAtMost(data.size)
                    return data.copyOfRange(i, end).joinToString("") { "%02x".format(it) }
                }
                i += len
            } else if (wire == 0) {
                // varint skip
                while (i < data.size && (data[i].toInt() and 0x80) != 0) i++
                i++
            } else {
                break
            }
        }
        return null
    }

    private fun handleWebRtc(message: CastV2.Message, out: DataOutputStream, peer: String) {
        val jsonStr = message.payloadUtf8 ?: ""
        Logger.i("cast", "webrtc message from $peer: ${jsonStr.take(500)}")
        try {
            val obj = JSONObject(jsonStr)
            val type = obj.optString("type")
            when (type) {
                "OFFER" -> {
                    val sdp = obj.optString("sdp")
                    if (sdp.isNotBlank()) {
                        Logger.i("cast", "received WebRTC OFFER, launching mirror activity")
                        // Ensure session exists
                        if (sessionId == null) {
                            sessionId = UUID.randomUUID().toString()
                            currentAppId = "CC1AD845" // Default media receiver
                        }
                        CastWebRtcManager.handleOffer(context, sdp, sessionId ?: "cast")
                    }
                }
                "ICE_CANDIDATE", "ADD_ICE_CANDIDATE", "CANDIDATE" -> {
                    // Different senders use different field names
                    val candidateObj = obj.optJSONObject("candidate") ?: obj
                    val sdp = candidateObj.optString("candidate").ifBlank { candidateObj.optString("sdp") }
                    val mid = candidateObj.optString("sdpMid").ifBlank { candidateObj.optString("sdpMidName") }
                    val index = candidateObj.optInt("sdpMLineIndex", candidateObj.optInt("label", 0))
                    if (sdp.isNotBlank()) {
                        CastWebRtcManager.handleIceCandidate(sdp, mid.ifBlank { null }, index)
                    }
                }
                else -> {
                    // Try to parse as generic candidate
                    if (jsonStr.contains("candidate")) {
                        val sdp = obj.optString("candidate")
                        if (sdp.isNotBlank()) {
                            val mid = obj.optString("sdpMid")
                            val idx = obj.optInt("sdpMLineIndex", 0)
                            CastWebRtcManager.handleIceCandidate(sdp, mid.ifBlank { null }, idx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("cast", "webrtc parse failed: ${e.message} raw=${jsonStr.take(200)}")
        }
    }

    private fun sendWebRtcAnswer(sdp: String) {
        val out = currentOutput ?: run {
            Logger.w("cast", "no output to send ANSWER")
            return
        }
        try {
            val payload = JSONObject()
                .put("type", "ANSWER")
                .put("sdp", sdp)
                .put("seqNum", 0)
            val msg = CastV2.Message(
                sourceId = currentSourceId,
                destinationId = currentDestId,
                namespace = CastV2.NS_WEBRTC,
                payloadUtf8 = payload.toString()
            )
            CastV2.write(out, msg)
            Logger.i("cast", "sent WebRTC ANSWER to $currentPeer")
        } catch (e: Exception) {
            Logger.e("cast", "send ANSWER failed: ${e.message}")
        }
    }

    private fun sendWebRtcIce(candidate: String, mid: String?, index: Int) {
        val out = currentOutput ?: return
        try {
            val cand = JSONObject()
                .put("candidate", candidate)
                .put("sdpMid", mid ?: "0")
                .put("sdpMLineIndex", index)
            val payload = JSONObject()
                .put("type", "ICE_CANDIDATE")
                .put("candidate", cand)
            val msg = CastV2.Message(
                sourceId = currentSourceId,
                destinationId = currentDestId,
                namespace = CastV2.NS_WEBRTC,
                payloadUtf8 = payload.toString()
            )
            CastV2.write(out, msg)
            Logger.i("cast", "sent local ICE to $currentPeer")
        } catch (e: Exception) {
            Logger.e("cast", "send ICE failed: ${e.message}")
        }
    }

    private fun handleReceiver(message: CastV2.Message, out: DataOutputStream, peer: String) {
        val payload = try {
            JSONObject(message.payloadUtf8 ?: "{}")
        } catch (_: Exception) {
            return
        }
        val requestId = payload.optInt("requestId", 0)
        when (payload.optString("type")) {
            "GET_STATUS" -> reply(out, message, CastV2.NS_RECEIVER, receiverStatus(requestId))

            "LAUNCH" -> {
                currentAppId = payload.optString("appId")
                sessionId = UUID.randomUUID().toString()
                Logger.i("cast", "launch requested by $peer for appId=$currentAppId -> session $sessionId")
                reply(out, message, CastV2.NS_RECEIVER, receiverStatus(requestId))
            }

            "STOP" -> {
                sessionId = null
                currentAppId = ""
                Logger.i("cast", "session stopped by $peer")
                CastWebRtcManager.stop()
                reply(out, message, CastV2.NS_RECEIVER, receiverStatus(requestId))
            }

            "SET_VOLUME" -> reply(out, message, CastV2.NS_RECEIVER, receiverStatus(requestId))

            else -> Logger.i("cast", "receiver: ${payload.optString("type")}")
        }
    }

    private fun receiverStatus(requestId: Int): JSONObject {
        val applications = JSONArray()
        val session = sessionId
        if (session != null) {
            applications.put(
                JSONObject()
                    .put("appId", currentAppId.ifBlank { "CC1AD845" })
                    .put("displayName", prefs.deviceName)
                    .put("isIdleScreen", false)
                    .put("launchedFromCloud", false)
                    .put("sessionId", session)
                    .put("statusText", prefs.deviceName)
                    .put("transportId", session)
                    .put(
                        "namespaces",
                        JSONArray()
                            .put(JSONObject().put("name", CastV2.NS_WEBRTC))
                            .put(JSONObject().put("name", CastV2.NS_MEDIA)),
                    ),
            )
        }
        return JSONObject()
            .put("requestId", requestId)
            .put("type", "RECEIVER_STATUS")
            .put(
                "status",
                JSONObject()
                    .put("applications", applications)
                    .put("userEq", JSONObject())
                    .put(
                        "volume",
                        JSONObject()
                            .put("controlType", "attenuation")
                            .put("level", 1.0)
                            .put("muted", false)
                            .put("stepInterval", 0.05),
                    ),
            )
    }

    private fun reply(
        out: DataOutputStream,
        to: CastV2.Message,
        namespace: String,
        payload: JSONObject,
    ) {
        try {
            CastV2.write(
                out,
                CastV2.Message(
                    // Source and destination swap: the sender addressed us, we answer it.
                    sourceId = to.destinationId.ifEmpty { "receiver-0" },
                    destinationId = to.sourceId.ifEmpty { "sender-0" },
                    namespace = namespace,
                    payloadUtf8 = payload.toString(),
                ),
            )
        } catch (e: Exception) {
            Logger.w("cast", "reply failed: ${e.message}")
        }
    }

    /** Stable 32-hex-digit device id, the form senders expect in the mDNS `id` record. */
    fun deviceId(): String {
        val uuid = Net.uuid(context).replace("-", "")
        return (uuid + uuid).take(32).lowercase()
    }

    private fun jsonType(payload: String?): String = try {
        JSONObject(payload ?: "{}").optString("type")
    } catch (_: Exception) {
        ""
    }
}
