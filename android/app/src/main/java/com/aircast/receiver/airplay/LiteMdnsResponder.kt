package com.aircast.receiver.airplay

import android.content.Context
import android.net.wifi.WifiManager
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.cast.CastV2
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.MulticastSocket

/**
 * Hand-rolled multicast DNS responder. Replaces JmDNS on Android 12+, where JmDNS
 * sockets (bound to a specific address, joined with `setInterface(InetAddress)`)
 * never receive multicast frames even with the Wi-Fi multicast lock held.
 *
 * This class does exactly what the DNS-SD spec needs for a receiver to be
 * discovered:
 *  - binds a `MulticastSocket` on `0.0.0.0:5353` with `SO_REUSEADDR`
 *  - joins `224.0.0.251` on the Wi-Fi `NetworkInterface` via
 *    `IP_ADD_MEMBERSHIP(NetworkInterface)` — the path Android's kernel permits
 *  - replies to any PTR query for our types with PTR+SRV+TXT+A answers
 *    (a standard unicast-response DNS-SD answer, no flush bit — senders ask
 *    again if they want cached records)
 *
 * Also holds the Wi-Fi `MulticastLock` for the socket's lifetime.
 */
class LiteMdnsResponder(private val context: Context) {

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var announcePackets: List<DatagramPacket> = emptyList()
    private var lock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null
    private val prefs = Prefs.get(context)

    data class TypeRecord(
        val serviceType: String,   // "_googlecast._tcp"
        val fqdn: String,          // "_googlecast._tcp.local."
        val instance: String,      // "AirCast (Samsung SM-S928B)"
        val port: Int,
        val txt: Map<String, String>,
    )

    fun start(airplayPort: Int, httpPort: Int) {
        if (running) return
        val ip = Net.primaryIp()
        if (ip.isBlank() || ip == "0.0.0.0") return
        running = true
        thread = Thread({ startInternal(ip, airplayPort, httpPort) }, "aircast-lite-mdns").apply {
            isDaemon = true
            start()
        }
    }

    private fun startInternal(ip: String, airplayPort: Int, httpPort: Int) {
        val iface: NetworkInterface = Net.interfaceFor(ip) ?: run {
            Logger.w("mdns", "lite responder: no interface for $ip")
            running = false
            return
        }
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wl = wifi?.createMulticastLock("aircast-lite-mdns")?.apply {
                setReferenceCounted(true)
            }
            wl?.acquire()
            lock = wl
            Logger.i("mdns", "lite responder multicast lock: ${wl?.isHeld}")

            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.soTimeout = 2000
            s.bind(InetSocketAddress(5353))
            s.joinGroup(InetSocketAddress("224.0.0.251", 5353), iface)
            try { s.timeToLive = 255 } catch (_: Exception) {
                try { s.timeToLive = 1 } catch (_: Exception) {}
            }
            socket = s
            Logger.i("mdns", "lite responder bound :5353 on ${iface.name}")

            val addr = InetAddress.getByName(ip)
            val deviceId = Net.deviceId(context)
            val uuid32 = Net.uuid(context).replace("-", "")
            val castId = (uuid32 + uuid32).take(32).lowercase()
            val name = prefs.deviceName

            // Records cloned from AirScreen v2.16.1 native mdns (libmdns.so) for
            // exact Meta Quest / Google Cast compatibility.
            val records = listOf(
                TypeRecord("_googlecast._tcp", "_googlecast._tcp.local.", name, CastV2.PORT, mapOf(
                    "id" to castId, "ve" to "05", "md" to "AirScreen",
                    "ic" to "/setup/icon.png", "fn" to name, "ca" to "4101",
                    "st" to "0", "rm" to name, "rs" to "", "nf" to "0",
                )),
                TypeRecord("_airplay._tcp", "_airplay._tcp.local.", name, airplayPort, mapOf(
                    "deviceid" to deviceId.chunked(2).joinToString(":"),
                    // features=61647880183 must match the /server-info plist so Apple
                    // senders accept the receiver for both video and audio streaming.
                    "features" to "61647880183", "model" to "AppleTV3,1",
                    "srcvers" to "220.68", "flags" to "0x24c",
                    "pk" to "0,1,2,3", "pi" to Net.uuid(context),
                )),
                TypeRecord("_raop._tcp", "_raop._tcp.local.", "$deviceId@$name", airplayPort, mapOf(
                    "txtvers" to "1", "ch" to "2", "cn" to "0,1", "et" to "0,1,2",
                    "sv" to "false", "da" to "true", "sr" to "44100", "ss" to "16",
                    "pw" to "0,3,5", "vn" to "3", "tp" to "UDP", "vs" to "220.68",
                    "am" to "AppleTV3,1", "sf" to "0x4",
                )),
                TypeRecord("_aircast._tcp", "_aircast._tcp.local.", name, httpPort, mapOf(
                    "ver" to "1.0.0", "id" to deviceId,
                    "http" to httpPort.toString(),
                    "model" to (android.os.Build.MODEL ?: "Android"),
                )),
            )
            Logger.i("mdns", "lite responder serving ${records.size} types on $ip")

            val buf = ByteArray(4096)
            val mcAddr = InetSocketAddress("224.0.0.251", 5353)
            // Build periodic DNS-SD announcements (broadcast all records every 2s).
            val announcePackets = ArrayList<DatagramPacket>()
            for (r in records) {
                val pkt = buildAnnouncement(records, r, addr)
                announcePackets.add(DatagramPacket(pkt, pkt.size, mcAddr))
            }
            Logger.i("mdns", "lite responder announcing ${announcePackets.size} types every 2s")

            val announcePacketsFinal: List<DatagramPacket> = ArrayList(announcePackets)

            // First announce immediately, then every 2 seconds. DNS-SD requires at
            // least 2 unsolicited announcements in the first 2 seconds (probe/announce),
            // then periodic announcements at >= 60s; we keep 2s for reliable discovery.
            try { s.send(announcePacketsFinal[0]) } catch (_: Exception) {}
            try { Thread.sleep(200); s.send(announcePacketsFinal[1 % announcePacketsFinal.size]) } catch (_: Exception) {}
            var announceIndex = 2
            var lastAnnounce = System.currentTimeMillis()
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (_: java.net.SocketTimeoutException) {
                        // nothing — fall through to periodic announce
                    }
                    val data = pkt.data
                    val len = pkt.length
                    val rx = parseAndAnswer(data, len, records, addr)
                    val (rxQuestions, answers) = rx
                    if (answers != null) {
                        // Answer back over multicast group (mDNS spec: answer goes to
                        // the group so all listeners cache the record).
                        val reply = DatagramPacket(answers, answers.size, mcAddr)
                        try {
                            s.send(reply)
                            Logger.i("mdns", "lite responder sent reply ${answers.size} bytes to group")
                        } catch (e: Exception) {
                            Logger.w("mdns", "lite responder send failed: ${e.message}")
                        }
                    } else if (rxQuestions.isNotEmpty()) {
                        Logger.i("mdns", "lite responder rx question: ${rxQuestions.joinToString { it.first }} (no answer)")
                    } else if (len >= 12) {
                        val hex = data.take(24).joinToString(" ") { "%02x".format(it) }
                        Logger.i("mdns", "lite responder rx packet $len bytes hex: $hex (parse failed)")
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastAnnounce >= 2000) {
                        try {
                            s.send(announcePacketsFinal[announceIndex % announcePacketsFinal.size])
                            Logger.i("mdns", "lite responder announced (total ${announceIndex + 1})")
                        } catch (_: Exception) {}
                        announceIndex++
                        lastAnnounce = now
                    }
                } catch (e: Exception) {
                    if (running) Logger.w("mdns", "lite responder loop: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.w("mdns", "lite responder failed: ${e.message}")
            running = false
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { if (lock?.isHeld == true) lock?.release() } catch (_: Exception) {}
        lock = null
        thread?.interrupt()
        thread = null
    }

    companion object {
        // ---- minimal DNS encoder/decoder -------------------------------------

        private fun encodeName(name: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var remaining = name
            if (!remaining.endsWith(".")) remaining += "."
            while (remaining.isNotEmpty()) {
                val dot = remaining.indexOf('.')
                val label = remaining.substring(0, dot)
                out.write(label.length)
                out.write(label.toByteArray())
                remaining = if (dot < 0) "" else remaining.substring(dot + 1)
            }
            out.write(0)
            return out.toByteArray()
        }

        private fun decodeName(data: ByteArray, offset: Int): Pair<String, Int> {
            var pos = offset
            val labels = ArrayList<String>()
            var jumped = false
            var end = offset
            var hops = 0
            while (true) {
                if (pos >= data.size) break
                val len = data[pos].toInt() and 0xFF
                when {
                    len == 0 -> { if (!jumped) end = pos + 1; break }
                    len and 0xC0 == 0xC0 -> {
                        val next = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        if (!jumped) end = pos + 2
                        pos = next
                        jumped = true
                        if (++hops > 20) break
                    }
                    len and 0xC0 != 0 -> {
                        val label = String(data, pos + 1, len)
                        labels.add(label)
                        pos += 1 + len
                        if (!jumped) end = pos
                    }
                    else -> break
                }
            }
            return Pair(labels.joinToString("."), end)
        }

        /** A single-type DNS-SD announcement (PTR+SRV+TXT+A), sent to the group. */
        private fun buildAnnouncement(records: List<TypeRecord>, one: TypeRecord, addr: InetAddress): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val header = ByteArray(12)
            header[0] = 0; header[1] = 0 // id=0
            header[2] = 0x84.toByte(); header[3] = 0x00.toByte() // QR+AA
            out.write(header)

            val hostFqdn = "aircast.local."
            // PTR
            out.write(encodeName(one.fqdn))
            writeShort(out, 12); writeShort(out, 1); writeInt(out, 4500) // PTR, IN, 4500s (standard TTL)
            val instFqdn = "${one.instance}.${one.fqdn}"
            val ptrTarget = encodeName(instFqdn)
            writeShort(out, ptrTarget.size)
            out.write(ptrTarget)
            // SRV
            out.write(encodeName(instFqdn))
            writeShort(out, 33); writeShort(out, 1); writeInt(out, 120)
            val srvBody = java.io.ByteArrayOutputStream()
            writeShort(srvBody, 0); writeShort(srvBody, 0); writeShort(srvBody, one.port)
            srvBody.write(encodeName(hostFqdn))
            writeShort(out, srvBody.size())
            out.write(srvBody.toByteArray())
            // TXT
            out.write(encodeName(instFqdn))
            writeShort(out, 16); writeShort(out, 1); writeInt(out, 4500)
            val txtBody = java.io.ByteArrayOutputStream()
            for ((k, v) in one.txt) {
                val kv = "$k=$v".toByteArray()
                txtBody.write(kv.size)
                txtBody.write(kv)
            }
            writeShort(out, txtBody.size())
            out.write(txtBody.toByteArray())
            // A
            out.write(encodeName(hostFqdn))
            writeShort(out, 1); writeShort(out, 1); writeInt(out, 120)
            val a = addr.address
            writeShort(out, 4)
            out.write(a)
            return out.toByteArray()
        }

        /** Returns the full DNS answer packet (or null if nothing to answer). */
        fun parseAndAnswer(
            data: ByteArray, len: Int,
            records: List<TypeRecord>,
            addr: InetAddress,
        ): Pair<List<Pair<String, Int>>, ByteArray?> {
            if (len < 12) return Pair(emptyList(), null)
            val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val isQuery = flags and 0x8000 == 0
            if (!isQuery || qdcount == 0) return Pair<List<Pair<String, Int>>, ByteArray?>(emptyList(), null)

            var pos = 12
            val questions = ArrayList<Pair<String, Int>>()
            repeat(qdcount) {
                if (pos >= len) return Pair<List<Pair<String, Int>>, ByteArray?>(emptyList(), null)
                val (name, next) = decodeName(data, pos)
                if (pos + 8 > len) return Pair<List<Pair<String, Int>>, ByteArray?>(emptyList(), null)
                val qtype = ((data[next].toInt() and 0xFF) shl 8) or (data[next + 1].toInt() and 0xFF)
                // qclass = data[next+2..3] (unicast-response bit may be set; ignore it)
                questions.add(name.lowercase() to qtype)
                pos = next + 4
            }

            // Standard mDNS answer: we answer ANY question with the full set of
            // records (PTR+SRV+TXT+A) for the types mentioned — the DNS-SD way.
            val wanted = records.filter { r ->
                questions.any { (qname, _) -> qname == r.fqdn.lowercase() }
            }
            if (wanted.isEmpty()) return Pair<List<Pair<String, Int>>, ByteArray?>(questions, null)

            val out = java.io.ByteArrayOutputStream()
            // header: id, flags=0x8400 (QR+AA), qd=0, an=variable, ns=0, ar=0
            val header = ByteArray(12)
            header[0] = (id ushr 8).toByte(); header[1] = id.toByte()
            header[2] = 0x84.toByte(); header[3] = 0x00.toByte()
            out.write(header)

            for (r in wanted) {
                // PTR: _type.local -> instance._type.local
                out.write(encodeName(r.fqdn))
                writeShort(out, 12); writeShort(out, 1); writeInt(out, 120) // PTR, IN, 120s
                val instFqdn = "${r.instance}.${r.fqdn}"
                val ptrTarget = encodeName(instFqdn)
                writeShort(out, ptrTarget.size)
                out.write(ptrTarget)

                // SRV: instance._type.local -> host.local port
                val hostFqdn = "aircast.local."
                out.write(encodeName(instFqdn))
                writeShort(out, 33); writeShort(out, 1); writeInt(out, 120) // SRV
                val srvBody = java.io.ByteArrayOutputStream()
                writeShort(srvBody, 0); writeShort(srvBody, 0); writeShort(srvBody, r.port)
                srvBody.write(encodeName(hostFqdn))
                writeShort(out, srvBody.size())
                out.write(srvBody.toByteArray())

                // TXT: instance._type.local -> txt record
                out.write(encodeName(instFqdn))
                writeShort(out, 16); writeShort(out, 1); writeInt(out, 120) // TXT
                val txtBody = java.io.ByteArrayOutputStream()
                for ((k, v) in r.txt) {
                    val kv = "$k=$v".toByteArray()
                    txtBody.write(kv.size)
                    txtBody.write(kv)
                }
                writeShort(out, txtBody.size())
                out.write(txtBody.toByteArray())
            }

            // A record for the hostname (asked by some senders directly)
            if (questions.any { (q, _) -> q == "aircast.local." }) {
                val hostFqdn = "aircast.local."
                out.write(encodeName(hostFqdn))
                writeShort(out, 1); writeShort(out, 1); writeInt(out, 120) // A
                val a = addr.address
                writeShort(out, 4)
                out.write(a)
            }

            return Pair(questions, out.toByteArray())
        }

        private fun writeShort(s: java.io.OutputStream, v: Int) {
            s.write((v ushr 8) and 0xFF)
            s.write(v and 0xFF)
        }

        private fun writeInt(s: java.io.OutputStream, v: Int) {
            s.write((v ushr 24) and 0xFF)
            s.write((v ushr 16) and 0xFF)
            s.write((v ushr 8) and 0xFF)
            s.write(v and 0xFF)
        }
    }
}
