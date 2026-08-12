package com.aircast.receiver.mirror

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * ACMP — the AirCast Mirror Protocol.
 *
 * Why a private protocol instead of Miracast, Cast or AirPlay mirroring: all three are
 * closed to third-party apps (see README §3). Since AirCast controls *both* ends of this
 * link, the transport can be exactly as complicated as it needs to be and no more:
 * length-prefixed frames of already-encoded H.264 and AAC over one TCP socket.
 *
 * Why TCP rather than RTP/UDP: on a LAN the loss rate is effectively zero, and a dropped
 * H.264 slice corrupts every following frame until the next keyframe — visible as several
 * seconds of smeared green. TCP's retransmit costs a few milliseconds of buffering and
 * removes that failure mode entirely. Nagle is disabled so a frame leaves immediately.
 *
 * Wire format, big-endian throughout:
 *
 *   handshake   "ACMP" u32(=1) u32(headerLen) headerJson
 *   reply       u32(len) json           { "ok": true }  or  { "ok": false, "error": "…" }
 *   packet      u8(type) u8(flags) u64(ptsUs) u32(len) payload
 */
object Acmp {

    const val PORT = 8323
    const val MAGIC = "ACMP"
    const val VERSION = 1

    // Packet types.
    const val T_VIDEO_CONFIG: Int = 1   // SPS/PPS (MediaFormat csd-0 + csd-1, concatenated)
    const val T_VIDEO: Int = 2
    const val T_AUDIO_CONFIG: Int = 3   // AAC AudioSpecificConfig (csd-0)
    const val T_AUDIO: Int = 4
    const val T_PING: Int = 5
    const val T_BYE: Int = 6
    const val T_GEOMETRY: Int = 7       // { "width": …, "height": … } after a rotation

    // Packet flags.
    const val F_KEYFRAME: Int = 1 shl 0

    /** A single decoded-from-the-wire packet. */
    class Packet(
        val type: Int,
        val flags: Int,
        val ptsUs: Long,
        val payload: ByteArray,
    ) {
        val isKeyframe: Boolean get() = (flags and F_KEYFRAME) != 0
    }

    /** What the sender declares up front. Values are advisory except width/height. */
    class Hello(
        val name: String,
        val model: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val hasAudio: Boolean,
        val sampleRate: Int,
        val channels: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("model", model)
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
            .put("videoBitrate", videoBitrate)
            .put("hasAudio", hasAudio)
            .put("sampleRate", sampleRate)
            .put("channels", channels)

        companion object {
            fun fromJson(o: JSONObject) = Hello(
                name = o.optString("name", "Android"),
                model = o.optString("model", ""),
                width = o.optInt("width", 1280),
                height = o.optInt("height", 720),
                fps = o.optInt("fps", 30),
                videoBitrate = o.optInt("videoBitrate", 6_000_000),
                hasAudio = o.optBoolean("hasAudio", false),
                sampleRate = o.optInt("sampleRate", 44_100),
                channels = o.optInt("channels", 2),
            )
        }
    }

    // ---- framing ------------------------------------------------------------

    @Throws(Exception::class)
    fun writeHandshake(out: DataOutputStream, hello: Hello) {
        val header = hello.toJson().toString().toByteArray(Charsets.UTF_8)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeInt(VERSION)
        out.writeInt(header.size)
        out.write(header)
        out.flush()
    }

    @Throws(Exception::class)
    fun readHandshake(input: DataInputStream): Hello? {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        val version = input.readInt()
        if (version != VERSION) return null
        val length = input.readInt()
        // A malformed or hostile length must not become a multi-gigabyte allocation.
        if (length !in 1..(64 * 1024)) return null
        val header = ByteArray(length)
        input.readFully(header)
        return Hello.fromJson(JSONObject(String(header, Charsets.UTF_8)))
    }

    @Throws(Exception::class)
    fun writeJson(out: DataOutputStream, o: JSONObject) {
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    @Throws(Exception::class)
    fun readJson(input: DataInputStream): JSONObject? {
        val length = input.readInt()
        if (length !in 1..(64 * 1024)) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    @Throws(Exception::class)
    fun writePacket(out: DataOutputStream, type: Int, flags: Int, ptsUs: Long, payload: ByteArray, length: Int = payload.size) {
        out.writeByte(type)
        out.writeByte(flags)
        out.writeLong(ptsUs)
        out.writeInt(length)
        if (length > 0) out.write(payload, 0, length)
        out.flush()
    }

    /** @return null at end of stream. */
    @Throws(Exception::class)
    fun readPacket(input: DataInputStream): Packet? {
        val type = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val flags = input.readUnsignedByte()
        val ptsUs = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IllegalStateException("bad packet length $length")
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return Packet(type, flags, ptsUs, payload)
    }

    /** 16 MB — far above any single 4K keyframe, far below anything that would OOM a TV box. */
    const val MAX_PAYLOAD: Int = 16 * 1024 * 1024

    // ---- quality presets ----------------------------------------------------

    /**
     * `height = 0` means "Auto": follow the source display, capped at 1080p because that
     * is where a phone encoder stops being able to hold 30 fps on battery, and because
     * the extra pixels are invisible at TV viewing distance.
     */
    class Preset(val label: String, val height: Int, val fps: Int) {
        fun resolve(sourceWidth: Int, sourceHeight: Int): Triple<Int, Int, Int> {
            val portrait = sourceHeight >= sourceWidth
            val longEdge = maxOf(sourceWidth, sourceHeight)
            val shortEdge = minOf(sourceWidth, sourceHeight)
            val targetShort = if (height <= 0) minOf(shortEdge, 1080) else minOf(shortEdge, height)
            // Preserve aspect ratio, then round both edges to even numbers: most H.264
            // encoders reject odd dimensions outright.
            val scale = targetShort.toDouble() / shortEdge.toDouble()
            val targetLong = (longEdge * scale).toInt()
            val w = if (portrait) even(targetShort) else even(targetLong)
            val h = if (portrait) even(targetLong) else even(targetShort)
            return Triple(w, h, bitrateFor(w, h, fps))
        }

        private fun even(v: Int) = if (v % 2 == 0) v else v - 1

        /**
         * ~0.07 bits per pixel per frame. Empirically that is the point where H.264
         * screen content stops showing blocking on text, which is what actually matters
         * when someone mirrors a phone to read something on the TV.
         */
        private fun bitrateFor(w: Int, h: Int, fps: Int): Int =
            (w.toLong() * h.toLong() * fps * 0.07).toInt().coerceIn(1_500_000, 40_000_000)

        companion object {
            val AUTO = Preset("auto", 0, 30)

            fun of(name: String?, fps: Int = 30): Preset = when (name?.lowercase()) {
                "720", "720p" -> Preset("720p", 720, fps)
                "1080", "1080p" -> Preset("1080p", 1080, fps)
                "1440", "1440p" -> Preset("1440p", 1440, fps)
                "2160", "2160p", "4k" -> Preset("4K", 2160, fps)
                else -> Preset("auto", 0, fps)
            }
        }
    }
}
