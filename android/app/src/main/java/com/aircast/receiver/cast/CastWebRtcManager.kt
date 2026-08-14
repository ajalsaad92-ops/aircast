package com.aircast.receiver.cast

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aircast.receiver.core.Logger
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * Manages WebRTC PeerConnection for Cast mirroring (Quest 3S).
 *
 * Flow:
 * 1. CastReceiver gets NS_WEBRTC OFFER with SDP from Quest
 * 2. We create PeerConnection, setRemoteDescription(OFFER)
 * 3. Create ANSWER, setLocalDescription, send ANSWER back via Cast channel
 * 4. Exchange ICE candidates via NS_WEBRTC ICE messages
 * 5. OnTrack -> render video in CastMirrorActivity
 *
 * This manager is a singleton, lives as long as ReceiverService lives.
 * For private APK, we assume "Bypass Device Auth" is enabled on Quest sender,
 * so we don't need to worry about signature validity - we just need to get to OFFER stage.
 *
 * TODO: For production without bypass, embed full signature table and use CastAuth to provide valid AuthResponse.
 */
object CastWebRtcManager {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceView: SurfaceViewRenderer? = null
    private var currentActivity: CastMirrorActivity? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Callback to send ANSWER / ICE back to Quest via Cast channel
    var sendAnswer: ((sdp: String) -> Unit)? = null
    var sendIce: ((candidate: String, sdpMid: String?, sdpMLineIndex: Int) -> Unit)? = null

    fun init(context: Context) {
        if (factory != null) return
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            eglBase = EglBase.create()
            val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            Logger.i("cast-webrtc", "PeerConnectionFactory initialized")
        } catch (e: Exception) {
            Logger.e("cast-webrtc", "factory init failed: ${e.message}")
            Log.e("CastWebRtc", "init failed", e)
        }
    }

    fun attachActivity(activity: CastMirrorActivity) {
        currentActivity = activity
        // If we already have a video track, attach renderer now
        surfaceView?.let { view ->
            activity.setRendererView(view)
        }
    }

    fun detachActivity(activity: CastMirrorActivity) {
        if (currentActivity === activity) currentActivity = null
    }

    fun handleOffer(context: Context, offerSdp: String, sessionId: String) {
        executor.execute {
            try {
                init(context)
                val f = factory ?: run {
                    Logger.e("cast-webrtc", "factory not ready")
                    return@execute
                }
                // Clean previous connection
                peerConnection?.close()
                peerConnection = null
                // Launch mirror activity
                val intent = Intent(context, CastMirrorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(CastMirrorActivity.EXTRA_SESSION_ID, sessionId)
                }
                context.startActivity(intent)

                val rtcConfig = PeerConnection.RTCConfiguration(
                    listOf(
                        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                    )
                ).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

                val observer = object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            Logger.i("cast-webrtc", "local ICE: ${it.sdpMid} ${it.sdpMLineIndex} ${it.sdp.take(60)}")
                            sendIce?.invoke(it.sdp, it.sdpMid, it.sdpMLineIndex)
                        }
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {
                        Logger.i("cast-webrtc", "onAddStream: ${stream?.videoTracks?.size} video tracks")
                        stream?.videoTracks?.firstOrNull()?.let { track ->
                            handleVideoTrack(track)
                        }
                    }
                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        if (track is VideoTrack) {
                            Logger.i("cast-webrtc", "onTrack video: ${track.id()}")
                            handleVideoTrack(track)
                        }
                    }
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        Logger.i("cast-webrtc", "signaling: $newState")
                    }
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Logger.i("cast-webrtc", "iceConnection: $newState")
                    }
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                        Logger.i("cast-webrtc", "iceGathering: $newState")
                    }
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Logger.i("cast-webrtc", "connection: $newState")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                }

                peerConnection = f.createPeerConnection(rtcConfig, observer)

                // Create SurfaceViewRenderer for remote video
                val egl = eglBase!!
                val renderer = SurfaceViewRenderer(context).apply {
                    init(egl.eglBaseContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                }
                surfaceView = renderer

                // Set remote description (OFFER from Quest)
                val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Logger.i("cast-webrtc", "remote OFFER set, creating ANSWER")
                        // Create answer
                        peerConnection?.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription?) {
                                answer?.let {
                                    Logger.i("cast-webrtc", "ANSWER created: ${it.description.take(120)}")
                                    peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            Logger.i("cast-webrtc", "local ANSWER set, sending to Quest")
                                            sendAnswer?.invoke(it.description)
                                        }
                                        override fun onCreateFailure(s: String?) {
                                            Logger.e("cast-webrtc", "setLocal ANSWER failed: $s")
                                        }
                                        override fun onSetFailure(s: String?) {
                                            Logger.e("cast-webrtc", "setLocal ANSWER failed: $s")
                                        }
                                    }, it)
                                }
                            }
                            override fun onCreateFailure(s: String?) {
                                Logger.e("cast-webrtc", "create ANSWER failed: $s")
                            }
                        }, MediaConstraints())
                    }
                    override fun onCreateFailure(s: String?) {
                        Logger.e("cast-webrtc", "setRemote OFFER failed: $s")
                    }
                    override fun onSetFailure(s: String?) {
                        Logger.e("cast-webrtc", "setRemote OFFER failed: $s")
                    }
                }, remoteDesc)
            } catch (e: Exception) {
                Logger.e("cast-webrtc", "handleOffer failed: ${e.message}")
                Log.e("CastWebRtc", "handleOffer", e)
            }
        }
    }

    private fun handleVideoTrack(track: VideoTrack) {
        videoTrack = track
        track.setEnabled(true)
        // Attach to renderer on main thread
        val renderer = surfaceView
        val activity = currentActivity
        if (renderer != null && activity != null) {
            // Must run on main? SurfaceViewRenderer can be updated from any thread but UI add must be main
            activity.runOnUiThread {
                try {
                    track.addSink(renderer)
                    activity.setRendererView(renderer)
                    Logger.i("cast-webrtc", "video track attached to renderer")
                } catch (e: Exception) {
                    Logger.e("cast-webrtc", "attach renderer failed: ${e.message}")
                }
            }
        } else {
            Logger.w("cast-webrtc", "no activity/renderer ready, track will be attached later")
            // If activity not yet ready, it will attach when it starts via attachActivity
        }
    }

    fun handleIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        executor.execute {
            try {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(candidate)
                Logger.i("cast-webrtc", "added remote ICE: $sdpMid $sdpMLineIndex")
            } catch (e: Exception) {
                Logger.e("cast-webrtc", "addIce failed: ${e.message}")
            }
        }
    }

    fun stop() {
        executor.execute {
            try {
                peerConnection?.close()
                peerConnection = null
                surfaceView?.release()
                surfaceView = null
                videoTrack = null
                Logger.i("cast-webrtc", "stopped")
            } catch (e: Exception) {
                Logger.e("cast-webrtc", "stop failed: ${e.message}")
            }
        }
    }

    // Simple SdpObserver stub
    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
