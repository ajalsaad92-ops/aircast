package com.aircast.receiver.mirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.aircast.receiver.core.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes one incoming ACMP stream onto a Surface, plus its audio track.
 *
 * The decoders live here rather than in the Activity because the socket outlives the
 * window: the user can rotate the TV box's output or the launcher can briefly steal
 * focus, and re-creating the whole session for that would drop the cast. The Activity
 * hands its Surface in via [attachSurface] and takes it back with [detachSurface].
 */
class MirrorSession(
    val hello: Acmp.Hello,
    val remoteIp: String,
    private val onEnded: (String) -> Unit,
) {
    private val closed = AtomicBoolean(false)

    @Volatile private var videoCodec: MediaCodec? = null
    @Volatile private var audioCodec: MediaCodec? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var surface: Surface? = null

    /** SPS/PPS, kept so the decoder can be rebuilt when a new Surface arrives. */
    @Volatile private var videoCsd: ByteArray? = null
    @Volatile private var audioCsd: ByteArray? = null

    @Volatile var width: Int = hello.width
        private set
    @Volatile var height: Int = hello.height
        private set

    /**
     * True once a keyframe has been fed since the last (re)configure. Feeding a decoder
     * mid-GOP produces a burst of green macroblocks, so everything before the first
     * keyframe is discarded on purpose.
     */
    @Volatile private var primed = false

    @Volatile var frames: Long = 0L
        private set
    @Volatile var bytes: Long = 0L
        private set

    // ---- surface lifecycle --------------------------------------------------

    @Synchronized
    fun attachSurface(newSurface: Surface) {
        if (closed.get()) return
        if (surface === newSurface && videoCodec != null) return
        releaseVideo()
        surface = newSurface
        val csd = videoCsd
        if (csd != null) startVideo(csd)
    }

    @Synchronized
    fun detachSurface() {
        releaseVideo()
        surface = null
    }

    // ---- packet intake ------------------------------------------------------

    fun onPacket(packet: Acmp.Packet) {
        if (closed.get()) return
        bytes += packet.payload.size
        when (packet.type) {
            Acmp.T_VIDEO_CONFIG -> onVideoConfig(packet.payload)
            Acmp.T_VIDEO -> onVideo(packet)
            Acmp.T_AUDIO_CONFIG -> onAudioConfig(packet.payload)
            Acmp.T_AUDIO -> onAudio(packet)
            Acmp.T_GEOMETRY -> onGeometry(packet.payload)
            Acmp.T_PING -> Unit
            Acmp.T_BYE -> close("sender stopped")
        }
    }

    @Synchronized
    private fun onVideoConfig(csd: ByteArray) {
        videoCsd = csd
        releaseVideo()
        if (surface != null) startVideo(csd)
    }

    @Synchronized
    private fun onGeometry(payload: ByteArray) {
        try {
            val o = org.json.JSONObject(String(payload, Charsets.UTF_8))
            val w = o.optInt("width", width)
            val h = o.optInt("height", height)
            if (w == width && h == height) return
            width = w
            height = h
            Logger.i("mirror", "geometry changed to ${w}x$h")
            // A rotation changes the SPS, so the sender always follows this with a fresh
            // config + keyframe; just tear the decoder down and wait for them.
            releaseVideo()
            videoCsd = null
        } catch (_: Exception) {
        }
    }

    private fun startVideo(csd: ByteArray) {
        val target = surface ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            // csd-0 must be exactly the SPS and csd-1 the PPS; the sender concatenates
            // them into one Annex-B blob, so split on the second start code.
            val split = splitCsd(csd)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(split.first))
            if (split.second != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(split.second!!))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, target, null, 0)
            codec.start()
            videoCodec = codec
            primed = false
            Logger.i("mirror", "video decoder up (${width}x$height)")
        } catch (e: Exception) {
            Logger.e("mirror", "video decoder failed: ${e.message}")
            videoCodec = null
        }
    }

    /** Annex-B: 00 00 00 01 <SPS> 00 00 00 01 <PPS>. */
    private fun splitCsd(csd: ByteArray): Pair<ByteArray, ByteArray?> {
        var second = -1
        var i = 4
        while (i + 3 < csd.size) {
            if (csd[i].toInt() == 0 && csd[i + 1].toInt() == 0 && csd[i + 2].toInt() == 0 && csd[i + 3].toInt() == 1) {
                second = i
                break
            }
            i++
        }
        return if (second <= 0) csd to null
        else csd.copyOfRange(0, second) to csd.copyOfRange(second, csd.size)
    }

    private fun onVideo(packet: Acmp.Packet) {
        val codec = videoCodec ?: return
        if (!primed) {
            if (!packet.isKeyframe) return
            primed = true
        }
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(packet.payload)
                codec.queueInputBuffer(index, 0, packet.payload.size, packet.ptsUs, 0)
                frames++
            }
            drainVideo(codec)
        } catch (e: Exception) {
            Logger.w("mirror", "video decode hiccup: ${e.message}")
            // An IllegalStateException here means the codec died (surface pulled away).
            // Rebuild on the next config rather than spamming a broken codec.
            if (e is IllegalStateException) {
                releaseVideo()
                videoCsd?.let { if (surface != null) startVideo(it) }
            }
        }
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            // render = true hands the frame straight to the Surface; there is no
            // intermediate copy and no GL context to manage.
            codec.releaseOutputBuffer(out, true)
        }
    }

    // ---- audio --------------------------------------------------------------

    @Synchronized
    private fun onAudioConfig(csd: ByteArray) {
        audioCsd = csd
        releaseAudio()
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, hello.sampleRate, hello.channels,
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()
            audioCodec = codec

            val channelMask =
                if (hello.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuffer = AudioTrack.getMinBufferSize(
                hello.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(8 * 1024)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(hello.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                // Two buffers of headroom: enough to ride out a scheduling hiccup on a
                // cheap TV box, short enough that lip-sync stays believable.
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track
            Logger.i("mirror", "audio decoder up (${hello.sampleRate} Hz, ${hello.channels}ch)")
        } catch (e: Exception) {
            Logger.w("mirror", "audio unavailable: ${e.message}")
            releaseAudio()
        }
    }

    private fun onAudio(packet: Acmp.Packet) {
        val codec = audioCodec ?: return
        val track = audioTrack ?: return
        try {
            val index = codec.dequeueInputBuffer(4_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(packet.payload)
                codec.queueInputBuffer(index, 0, packet.payload.size, packet.ptsUs, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                val buffer = codec.getOutputBuffer(out)
                if (buffer != null && info.size > 0) {
                    val chunk = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(chunk, 0, info.size)
                    track.write(chunk, 0, chunk.size)
                }
                codec.releaseOutputBuffer(out, false)
            }
        } catch (e: Exception) {
            Logger.w("mirror", "audio decode hiccup: ${e.message}")
        }
    }

    // ---- teardown -----------------------------------------------------------

    private fun releaseVideo() {
        val codec = videoCodec
        videoCodec = null
        primed = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
    }

    private fun releaseAudio() {
        val codec = audioCodec
        val track = audioTrack
        audioCodec = null
        audioTrack = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
    }

    @Synchronized
    fun close(reason: String) {
        if (closed.getAndSet(true)) return
        releaseVideo()
        releaseAudio()
        surface = null
        onEnded(reason)
    }

    val isClosed: Boolean get() = closed.get()
}
