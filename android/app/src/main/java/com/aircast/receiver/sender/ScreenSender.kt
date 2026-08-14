package com.aircast.receiver.sender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.aircast.receiver.core.Logger
import com.aircast.receiver.input.RemoteInputService
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native screen-cast SENDER: captures this device via MediaProjection and streams it
 * over WebRTC to another AirCast receiver on the LAN, speaking the same HTTP signalling
 * the browser sender page uses (/mirror/offer, /answer, /ice, /ping, /bye).
 *
 * A "control" data channel carries reverse-input events back from the receiver; each is
 * mapped to this device's pixels and injected through RemoteInputService (point 7).
 */
class ScreenSender(private val context: Context) {

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var controlChannel: DataChannel? = null

    private val net = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var pollThread: Thread? = null

    private var peerId: String = UUID.randomUUID().toString()
    private var base: String = ""
    private var pin: String = ""
    private var senderName: String = "AirCast phone"
    private var iceSince = 0
    private var screenW = 0
    private var screenH = 0

    fun start(projectionData: Intent, host: String, port: Int, pin: String, name: String) {
        if (running.getAndSet(true)) return
        base = "http://$host:$port"
        this.pin = pin
        senderName = name
        net.execute {
            try {
                setup(projectionData)
            } catch (e: Exception) {
                Logger.e("sender", "start failed: ${e.message}")
                stop()
            }
        }
    }

    private fun setup(projectionData: Intent) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels

        // Keep the encoder happy on very large panels; preserve aspect, even dimensions.
        var capW = screenW
        var capH = screenH
        val maxDim = 1920
        if (maxOf(capW, capH) > maxDim) {
            val s = maxDim.toFloat() / maxOf(capW, capH)
            capW = (capW * s).toInt() / 2 * 2
            capH = (capH * s).toInt() / 2 * 2
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions(),
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
        val f = factory!!

        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Logger.i("sender", "projection stopped by system")
                stop()
            }
        })
        capturer = cap
        val source = f.createVideoSource(true)
        videoSource = source
        surfaceHelper = SurfaceTextureHelper.create("cap", eglBase!!.eglBaseContext)
        cap.initialize(surfaceHelper, context, source.capturerObserver)
        cap.startCapture(capW, capH, 30)
        val track = f.createVideoTrack("screen", source)
        videoTrack = track

        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val connection = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) { c?.let { postCandidate(it) } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { Logger.i("sender", "ice: $s") }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Logger.i("sender", "conn: $s")
                if (s == PeerConnection.PeerConnectionState.FAILED) stop()
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
        })
        if (connection == null) {
            Logger.e("sender", "createPeerConnection returned null")
            stop()
            return
        }
        pc = connection
        connection.addTrack(track, listOf("screen"))

        controlChannel = connection.createDataChannel("control", DataChannel.Init())
        controlChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer?) { buffer?.let { onControl(it) } }
        })

        connection.createOffer(object : SdpObs() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                connection.setLocalDescription(object : SdpObs() {
                    override fun onSetSuccess() { net.execute { signal(sdp.description) } }
                }, sdp)
            }
        }, MediaConstraints())
    }

    private fun signal(offerSdp: String) {
        try {
            val body = JSONObject()
                .put("id", peerId).put("name", senderName)
                .put("sdp", offerSdp).put("pin", pin)
            val res = httpPost("/mirror/offer", body.toString())
            if (res == null) {
                Logger.e("sender", "offer POST failed")
                stop()
                return
            }
            peerId = try { JSONObject(res).optString("id", peerId) } catch (_: Exception) { peerId }
            pollThread = Thread({ pollLoop() }, "sender-poll").apply { isDaemon = true }.also { it.start() }
        } catch (e: Exception) {
            Logger.e("sender", "signal failed: ${e.message}")
            stop()
        }
    }

    private fun pollLoop() {
        var gotAnswer = false
        var tries = 0
        while (running.get() && !gotAnswer && tries < 120) {
            val a = httpGet("/mirror/answer?id=$peerId")
            if (!a.isNullOrBlank() && a != "{}") {
                val sdp = try { JSONObject(a).optString("sdp") } catch (_: Exception) { "" }
                if (sdp.isNotBlank()) {
                    pc?.setRemoteDescription(object : SdpObs() {}, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    gotAnswer = true
                    break
                }
            }
            sleep(300); tries++
        }
        if (!gotAnswer) {
            Logger.w("sender", "no answer from receiver")
            stop()
            return
        }
        var tick = 0
        while (running.get()) {
            val ice = httpGet("/mirror/ice?id=$peerId&since=$iceSince")
            if (ice != null && ice.startsWith("[")) {
                try {
                    val arr = JSONArray(ice)
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        pc?.addIceCandidate(
                            IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")),
                        )
                    }
                    iceSince += arr.length()
                } catch (_: Exception) {}
            }
            if (tick % 10 == 0) httpGet("/mirror/ping?id=$peerId")
            tick++
            sleep(500)
        }
    }

    private fun postCandidate(c: IceCandidate) {
        net.execute {
            try {
                val cand = JSONObject()
                    .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                httpPost("/mirror/ice?id=$peerId", JSONObject().put("candidate", cand).toString())
            } catch (_: Exception) {}
        }
    }

    private fun onControl(buf: DataChannel.Buffer) {
        try {
            val bytes = ByteArray(buf.data.remaining())
            buf.data.get(bytes)
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val svc = RemoteInputService.instance ?: return
            when (o.optString("t")) {
                "tap" -> svc.tap(
                    (o.optDouble("x") * screenW).toFloat(),
                    (o.optDouble("y") * screenH).toFloat(),
                )
                "swipe" -> svc.swipe(
                    (o.optDouble("x1") * screenW).toFloat(),
                    (o.optDouble("y1") * screenH).toFloat(),
                    (o.optDouble("x2") * screenW).toFloat(),
                    (o.optDouble("y2") * screenH).toFloat(),
                    o.optLong("ms", 200L),
                )
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)
        pollThread = null
        net.execute {
            try { if (wasRunning) httpPost("/mirror/bye?id=$peerId", "{}") } catch (_: Exception) {}
            try { capturer?.stopCapture() } catch (_: Exception) {}
            try { controlChannel?.dispose() } catch (_: Exception) {}
            try { pc?.close() } catch (_: Exception) {}
            try { capturer?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            try { surfaceHelper?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { eglBase?.release() } catch (_: Exception) {}
            pc = null; capturer = null; videoSource = null
            surfaceHelper = null; factory = null; eglBase = null; controlChannel = null
            Logger.i("sender", "stopped")
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    // ---- tiny HTTP client for the /mirror signalling ----
    private fun httpPost(path: String, body: String): String? = http("POST", path, body)
    private fun httpGet(path: String): String? = http("GET", path, null)

    private fun http(method: String, path: String, body: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 6000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            when {
                code == 204 -> ""
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                else -> null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private abstract class SdpObs : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Logger.w("sender", "sdp create fail: $p0") }
        override fun onSetFailure(p0: String?) { Logger.w("sender", "sdp set fail: $p0") }
    }
}
