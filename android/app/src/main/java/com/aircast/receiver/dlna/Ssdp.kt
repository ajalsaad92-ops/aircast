package com.aircast.receiver.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.aircast.receiver.core.HttpServer
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSDP discovery for the MediaRenderer.
 *
 * Two things make this fail silently on Android and both are handled here:
 *   1. Without a [WifiManager.MulticastLock] the Wi-Fi chip filters multicast frames,
 *      so `M-SEARCH` packets never reach userspace and the renderer is simply invisible.
 *   2. Replies must be sent *unicast* from a separate socket back to the searcher's
 *      source port — answering on the multicast socket works on some stacks and not others.
 */
class Ssdp(
    private val context: Context,
    private val httpPortProvider: () -> Int,
) {

    private val prefs = Prefs.get(context)
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var listenThread: Thread? = null
    private var announceThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val random = Random()

    private val udn: String get() = "uuid:" + Net.uuid(context)
    private val location: String get() = "http://${Net.primaryIp()}:${httpPortProvider()}/description.xml"

    private val targets: List<Pair<String, String>>
        get() = listOf(
            "upnp:rootdevice" to "$udn::upnp:rootdevice",
            udn to udn,
            Upnp.DEVICE_TYPE to "$udn::${Upnp.DEVICE_TYPE}",
            Upnp.SVC_AVTRANSPORT to "$udn::${Upnp.SVC_AVTRANSPORT}",
            Upnp.SVC_RENDERING to "$udn::${Upnp.SVC_RENDERING}",
            Upnp.SVC_CONNECTION to "$udn::${Upnp.SVC_CONNECTION}",
        )

    fun start() {
        if (running.getAndSet(true)) return
        acquireMulticastLock()
        try {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(SSDP_PORT))
            ms.timeToLive = 4
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val nif = Net.interfaceFor(Net.primaryIp())
            try {
                if (nif != null) ms.joinGroup(InetSocketAddress(group, SSDP_PORT), nif)
                else ms.joinGroup(group)
            } catch (e: Exception) {
                Logger.w("ssdp", "joinGroup on ${nif?.name} failed (${e.message}); retrying unbound")
                ms.joinGroup(group)
            }
            socket = ms
            listenThread = Thread(::listenLoop, "ssdp-listen").apply { isDaemon = true; start() }
            announceThread = Thread(::announceLoop, "ssdp-announce").apply { isDaemon = true; start() }
            Logger.i("ssdp", "discovery active on ${Net.primaryIp()} -> $location")
        } catch (e: Exception) {
            running.set(false)
            releaseMulticastLock()
            Logger.e("ssdp", "failed to start: ${e.message}")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { sendByeBye() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        listenThread?.interrupt()
        announceThread?.interrupt()
        releaseMulticastLock()
        Gena.clear()
        Logger.i("ssdp", "discovery stopped")
    }

    /** Re-announce immediately — used when the IP changes or the user renames the device. */
    fun refresh() {
        if (!running.get()) return
        Thread {
            try { sendAlive() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    // ---- receive ------------------------------------------------------------

    private fun listenLoop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            val socket = this.socket ?: break
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                if (running.get()) Logger.w("ssdp", "receive failed: ${e.message}")
                break
            }
            val message = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
            if (!message.startsWith("M-SEARCH", ignoreCase = true)) continue
            handleSearch(message, packet.address, packet.port)
        }
    }

    private fun handleSearch(message: String, from: InetAddress, port: Int) {
        val headers = HashMap<String, String>()
        for (line in message.split("\r\n", "\n")) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.US)] =
                line.substring(i + 1).trim()
        }
        if (headers["man"]?.trim('"') != "ssdp:discover") return
        val st = headers["st"] ?: return
        val mx = (headers["mx"]?.toIntOrNull() ?: 1).coerceIn(0, 3)

        val matches = when (st) {
            "ssdp:all" -> targets
            else -> targets.filter { it.first == st }
        }
        if (matches.isEmpty()) return

        val delay = if (mx > 0) random.nextInt(mx * 1000).toLong() else 0L
        Thread {
            try {
                if (delay > 0) Thread.sleep(delay)
                DatagramSocket().use { reply ->
                    for ((nt, usn) in matches) {
                        val body = searchResponse(if (st == "ssdp:all") nt else st, usn)
                        val bytes = body.toByteArray(Charsets.ISO_8859_1)
                        reply.send(DatagramPacket(bytes, bytes.size, from, port))
                    }
                }
            } catch (e: Exception) {
                Logger.w("ssdp", "reply to ${from.hostAddress} failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- advertise ----------------------------------------------------------

    private fun announceLoop() {
        // The spec wants each advertisement sent more than once because UDP drops.
        try {
            repeat(3) {
                if (!running.get()) return
                sendAlive()
                Thread.sleep(300)
            }
            while (running.get()) {
                Thread.sleep(REANNOUNCE_INTERVAL_MS)
                if (running.get()) sendAlive()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Logger.w("ssdp", "announce loop ended: ${e.message}")
        }
    }

    private fun sendAlive() {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        DatagramSocket().use { out ->
            for ((nt, usn) in targets) {
                val body = notify(nt, usn, "ssdp:alive")
                val bytes = body.toByteArray(Charsets.ISO_8859_1)
                out.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            }
        }
    }

    private fun sendByeBye() {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        DatagramSocket().use { out ->
            for ((nt, usn) in targets) {
                val body = notify(nt, usn, "ssdp:byebye")
                val bytes = body.toByteArray(Charsets.ISO_8859_1)
                out.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            }
        }
    }

    private fun searchResponse(st: String, usn: String) = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
        append("DATE: ${HttpServer.httpDate()}\r\n")
        append("EXT:\r\n")
        append("LOCATION: $location\r\n")
        append("SERVER: ${serverToken()}\r\n")
        append("ST: $st\r\n")
        append("USN: $usn\r\n")
        append("BOOTID.UPNP.ORG: ${prefs.bootId}\r\n")
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    private fun notify(nt: String, usn: String, nts: String) = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
        append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
        if (nts == "ssdp:alive") append("LOCATION: $location\r\n")
        append("NT: $nt\r\n")
        append("NTS: $nts\r\n")
        append("SERVER: ${serverToken()}\r\n")
        append("USN: $usn\r\n")
        append("BOOTID.UPNP.ORG: ${prefs.bootId}\r\n")
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    private fun serverToken() =
        "Android/${android.os.Build.VERSION.RELEASE} ${HttpServer.SERVER_TOKEN}"

    // ---- multicast lock -----------------------------------------------------

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wm.createMulticastLock("aircast-ssdp")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Logger.w("ssdp", "multicast lock unavailable: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        private const val MAX_AGE = 1800
        private const val REANNOUNCE_INTERVAL_MS = 15 * 60 * 1000L
    }
}
