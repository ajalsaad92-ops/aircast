package com.aircast.receiver.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The CASTV2 wire format, hand-rolled.
 *
 * Google Cast frames every control message as a length-prefixed protobuf `CastMessage`
 * with exactly seven fields. Pulling in protobuf-java and a `.proto` toolchain to encode
 * seven fields would add a dependency, a build step and a generated-source directory for
 * roughly eighty lines of varint handling — so the codec is written out here instead.
 *
 *   message CastMessage {
 *     required ProtocolVersion protocol_version = 1;  // varint, always 0
 *     required string source_id                 = 2;
 *     required string destination_id            = 3;
 *     required string namespace                 = 4;
 *     required PayloadType payload_type          = 5;  // varint: 0 = STRING, 1 = BINARY
 *     optional string payload_utf8               = 6;
 *     optional bytes  payload_binary             = 7;
 *   }
 *
 * On the wire each message is preceded by its length as a 4-byte big-endian integer.
 */
object CastV2 {

    const val PORT = 8009

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_DEVICEAUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    const val NS_WEBRTC = "urn:x-cast:com.google.cast.webrtc"

    const val PAYLOAD_STRING = 0
    const val PAYLOAD_BINARY = 1

    class Message(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payloadType: Int = PAYLOAD_STRING,
        val payloadUtf8: String? = null,
        val payloadBinary: ByteArray? = null,
    ) {
        override fun toString(): String =
            "$namespace $sourceId->$destinationId ${payloadUtf8?.take(160) ?: "<${payloadBinary?.size ?: 0} bytes>"}"
    }

    // ---- encode -------------------------------------------------------------

    fun encode(message: Message): ByteArray {
        val body = ByteArrayOutputStream(256)
        writeVarintField(body, 1, 0L)                       // protocol_version = CASTV2_1_0
        writeStringField(body, 2, message.sourceId)
        writeStringField(body, 3, message.destinationId)
        writeStringField(body, 4, message.namespace)
        writeVarintField(body, 5, message.payloadType.toLong())
        message.payloadUtf8?.let { writeStringField(body, 6, it) }
        message.payloadBinary?.let { writeBytesField(body, 7, it) }
        return body.toByteArray()
    }

    fun write(out: DataOutputStream, message: Message) {
        val bytes = encode(message)
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    // ---- decode -------------------------------------------------------------

    /** @return null at end of stream. */
    fun read(input: DataInputStream): Message? {
        val length = try {
            input.readInt()
        } catch (_: Exception) {
            return null
        }
        // A Cast control message is a few hundred bytes; anything past 1 MB is either a
        // desynchronised stream or someone probing the port.
        if (length !in 1..(1 shl 20)) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Message {
        var i = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = PAYLOAD_STRING
        var payloadUtf8: String? = null
        var payloadBinary: ByteArray? = null

        while (i < bytes.size) {
            val (key, afterKey) = readVarint(bytes, i)
            i = afterKey
            val field = (key ushr 3).toInt()
            when ((key and 0x7L).toInt()) {
                0 -> { // varint
                    val (value, next) = readVarint(bytes, i)
                    i = next
                    if (field == 5) payloadType = value.toInt()
                }
                2 -> { // length-delimited
                    val (len, afterLen) = readVarint(bytes, i)
                    i = afterLen
                    val end = (i + len.toInt()).coerceAtMost(bytes.size)
                    val slice = bytes.copyOfRange(i, end)
                    i = end
                    when (field) {
                        2 -> sourceId = String(slice, Charsets.UTF_8)
                        3 -> destinationId = String(slice, Charsets.UTF_8)
                        4 -> namespace = String(slice, Charsets.UTF_8)
                        6 -> payloadUtf8 = String(slice, Charsets.UTF_8)
                        7 -> payloadBinary = slice
                    }
                }
                5 -> i += 4
                1 -> i += 8
                else -> i = bytes.size // unknown wire type: stop rather than misparse
            }
        }
        return Message(sourceId, destinationId, namespace, payloadType, payloadUtf8, payloadBinary)
    }

    // ---- varints ------------------------------------------------------------

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) break
        }
        return result to i
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarint(out, (field.toLong() shl 3) or 0L)
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) =
        writeBytesField(out, field, value.toByteArray(Charsets.UTF_8))

    private fun writeBytesField(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        writeVarint(out, (field.toLong() shl 3) or 2L)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }
}
