package com.aircast.receiver.cast

import android.content.Context
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
 * The control half of a Google Cast receiver.
 *
 * Scope, stated plainly: this speaks enough CASTV2 for a sender to *discover* AirCast,
 * open a connection, keep it alive and launch a session. It does **not** yet carry the
 * mirrored picture — that is the `urn:x-cast:com.google.cast.webrtc` OFFER/ANSWER plus a
 * Cast-flavoured RTP stream, and it is a substantially bigger piece of work.
 *
 * It is built in this order deliberately. The genuinely unknown question is whether a
 * sender will talk to a receiver holding no Google-issued device certificate; every
 * remaining step is known-but-laborious. Getting to "AirCast appears in the list and a
 * session opens" answers that question for the price of one build, so the log lines
 * below are the real deliverable of this stage.
 */
class CastReceiver(private val context: Context) {

    private val prefs = Prefs.get(context)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Set once a sender launches an app, so a second sender sees the receiver as busy. */
    @Volatile private var sessionId: String? = null
    @Volatile private var currentAppId: String = ""

    fun start() {
        if (running.getAndSet(true)) return
        val factory = TlsFactory.serverSocketFactory(context)
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
            Logger.i("cast", "Cast control channel listening on ${CastV2.PORT}")
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
        pool.shutdownNow()
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
        Sessions.touch("cast", peer)
        try {
            socket.tcpNoDelay = true
            // Senders idle between heartbeats; the timeout only needs to outlive one.
            socket.soTimeout = 60_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream())

            while (running.get() && !socket.isClosed) {
                val message = CastV2.read(input) ?: break
                handle(message, output, peer)
            }
        } catch (e: Exception) {
            Logger.w("cast", "session with $peer ended: ${e.message}")
        } finally {
            Sessions.end("cast", peer)
            try { socket.close() } catch (_: Exception) {}
            Logger.i("cast", "sender $peer disconnected")
        }
    }

    private fun handle(message: CastV2.Message, out: DataOutputStream, peer: String) {
        when (message.namespace) {
            CastV2.NS_CONNECTION -> {
                val type = jsonType(message.payloadUtf8)
                Logger.i("cast", "connection: $type from $peer")
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
                    "device auth challenge from $peer (${message.payloadBinary?.size ?: 0} bytes) " +
                        "- no Google device certificate to answer with",
                )
            }

            CastV2.NS_RECEIVER -> handleReceiver(message, out, peer)

            CastV2.NS_WEBRTC -> {
                // Arriving here at all would mean discovery, auth and launch all passed.
                Logger.w("cast", "mirroring offer received - streaming layer not implemented yet")
            }

            else -> Logger.i("cast", "unhandled namespace ${message.namespace}")
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
                Logger.i("cast", "launch requested by $peer for appId=$currentAppId")
                reply(out, message, CastV2.NS_RECEIVER, receiverStatus(requestId))
            }

            "STOP" -> {
                sessionId = null
                currentAppId = ""
                Logger.i("cast", "session stopped by $peer")
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
                    .put("appId", currentAppId)
                    .put("displayName", prefs.deviceName)
                    .put("isIdleScreen", false)
                    .put("launchedFromCloud", false)
                    .put("sessionId", session)
                    .put("statusText", prefs.deviceName)
                    .put("transportId", session)
                    .put(
                        "namespaces",
                        JSONArray().put(JSONObject().put("name", CastV2.NS_WEBRTC)),
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
