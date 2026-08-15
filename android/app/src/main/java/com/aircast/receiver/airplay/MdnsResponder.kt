package com.aircast.receiver.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Owns the multicast DNS responder used for *all* LAN discovery (Cast, AirPlay/RAOP and
 * the companion sender page).
 *
 * Why this exists alongside [NsdAdvertiser]: on many OEM stacks (Samsung, Android TV)
 * the system `NsdManager` registers the service with its internal responder but never
 * answers multicast queries from foreign senders — the device is effectively invisible
 * in the Cast/AirPlay picker even though `registerService` reported success.
 *
 * Android blocks multicast UDP packets from reaching applications by default. Two things
 * are required before JmDNS can hear or emit multicast:
 *  1. `WifiManager.MulticastLock` — asks the Wi-Fi stack to deliver multicast frames to
 *     this app. Without it JmDNS joins the group, sends announcements, but the NIC
 *     silently discards everything (verified: zero replies to active PTR probes).
 *  2. Binding JmDNS to the correct `NetworkInterface` (not just an address) so the
 *     membership socket option lands on the Wi-Fi NIC that actually carries multicast.
 */
class MdnsResponder(private val context: Context) {

    private val prefs = Prefs.get(context)
    @Volatile private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var started = false

    fun start(airplayPort: Int, httpPort: Int) {
        if (started) return
        started = true
        val ip = Net.primaryIp()
        if (ip.isBlank() || ip == "0.0.0.0") return
        Thread({ startInternal(ip, airplayPort, httpPort) }, "aircast-mdns-resp").apply {
            isDaemon = true
        }.start()
    }

    private fun startInternal(ip: String, airplayPort: Int, httpPort: Int) {
        stopInternal()
        try {
            // 1. Acquire the Wi-Fi multicast lock BEFORE creating JmDNS.
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("aircast-mdns-resp")?.apply {
                setReferenceCounted(true)
            }
            lock?.acquire()
            multicastLock = lock
            val lockHeld = lock?.isHeld == true
            Logger.i("mdns", "multicast lock acquire: $lockHeld (lock=${lock != null})")

            // 2. Bind JmDNS to the Wi-Fi NetworkInterface, not just the address.
            //    JmDNS 3.5.9 `JmDNS.create(InetAddress, String)` joins the group via
            //    AddMembership on the interface the address belongs to. Android's
            //    ConnectivityManager may report the address via VPN/TUN first, which
            //    puts membership on the wrong interface — resolve the NIC explicitly.
            val iface = Net.interfaceFor(ip)
            val addr = InetAddress.getByName(ip)
            // JmDNS 3.5.9: create(InetAddress, String) binds to the interface the
            // address belongs to and joins 224.0.0.251 on it. With the Wi-Fi multicast
            // lock held (acquired above), the NIC delivers the group to this socket.
            val dns = JmDNS.create(addr, "aircast")
            jmdns = dns
            Logger.i("mdns", "jmdns bound on $ip iface=${iface?.name}")

            val name = prefs.deviceName
            val deviceId = Net.deviceId(context)
            val uuid32 = Net.uuid(context).replace("-", "")
            val castId = (uuid32 + uuid32).take(32).lowercase()

            // _googlecast._tcp — what the Quest / Chrome cast picker looks for.
            register(dns, ServiceInfo.create(
                "_googlecast._tcp.local.", "local.", name, 8009, 0, 0,
                mapOf(
                    "id" to castId, "ve" to "05", "md" to "AirCast Receiver",
                    "ic" to "/setup/icon.png", "fn" to name, "ca" to "2136",
                    "st" to "0", "rm" to name, "rs" to "", "nf" to "0",
                ),
            ), "googlecast")

            // _airplay._tcp — iOS / macOS screen & audio.
            register(dns, ServiceInfo.create(
                "_airplay._tcp.local.", "local.", name, airplayPort, 0, 0,
                mapOf(
                    "deviceid" to deviceId.chunked(2).joinToString(":"),
                    "features" to "0x77", "model" to "AppleTV3,2",
                    "srcvers" to "220.68", "vv" to "2", "flags" to "0x4",
                    "pk" to "", "pi" to Net.uuid(context),
                ),
            ), "airplay")

            // _raop._tcp — AirTunes audio; instance name must be <12-hex>@<name>.
            register(dns, ServiceInfo.create(
                "_raop._tcp.local.", "local.", "$deviceId@$name", airplayPort, 0, 0,
                mapOf(
                    "txtvers" to "1", "ch" to "2", "cn" to "0,1", "et" to "0,1",
                    "sv" to "false", "da" to "true", "sr" to "44100", "ss" to "16",
                    "pw" to "false", "vn" to "3", "tp" to "UDP", "vs" to "220.68",
                    "am" to "AppleTV3,2", "sf" to "0x4",
                ),
            ), "raop")

            // _aircast._tcp — the companion sender page.
            register(dns, ServiceInfo.create(
                "_aircast._tcp.local.", "local.", name, httpPort, 0, 0,
                mapOf(
                    "ver" to "1.0.0", "id" to deviceId,
                    "http" to httpPort.toString(),
                    "model" to (Build.MODEL ?: "Android"),
                ),
            ), "aircast")

            Logger.i("mdns", "multicast responder up on $ip (${dns.name}) iface=${iface?.name}")
        } catch (e: Exception) {
            Logger.w("mdns", "multicast responder failed: ${e.message}")
            started = false
        }
    }

    private fun register(dns: JmDNS, info: ServiceInfo, label: String) {
        try {
            dns.registerService(info)
            Logger.i("mdns", "responder registered _$label over multicast")
        } catch (e: Exception) {
            Logger.w("mdns", "responder $label failed: ${e.message}")
        }
    }

    fun stop() {
        started = false
        Thread({ stopInternal() }, "aircast-mdns-stop").apply { isDaemon = true }.start()
    }

    private fun stopInternal() {
        try { jmdns?.close() } catch (_: Exception) {}
        jmdns = null
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
