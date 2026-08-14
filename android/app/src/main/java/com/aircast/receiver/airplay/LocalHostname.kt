package com.aircast.receiver.airplay

import android.content.Context
import android.net.wifi.WifiManager
import com.aircast.receiver.core.Logger
import java.net.InetAddress
import javax.jmdns.JmDNS

/**
 * Publishes a stable `aircast.local` hostname over mDNS so a saved bookmark survives
 * the receiver's IP changing (DHCP). Browsers that resolve `.local` — Chromium, and
 * therefore the Meta Quest browser — can then reach the box by name rather than a
 * numeric address that drifts.
 *
 * Best-effort by design: it runs its own jmDNS responder alongside the system's
 * NsdManager. If an OEM blocks the multicast socket the receiver still works over its
 * IP; only the friendly name is lost, so every failure here is logged, never thrown.
 */
class LocalHostname(private val context: Context) {

    @Volatile private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null

    /** (Re)publish `aircast.local` pointing at [ip]. No-op for a blank/zero address. */
    fun start(ip: String) {
        stop()
        if (ip.isBlank() || ip == "0.0.0.0") return
        // jmDNS.create does blocking multicast I/O — never on the caller's thread.
        Thread({
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("aircast-mdns")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
                jmdns = JmDNS.create(InetAddress.getByName(ip), HOST)
                Logger.i("mdns", "hostname $HOST.local -> $ip")
            } catch (e: Exception) {
                Logger.w("mdns", "hostname publish failed: ${e.message}")
            }
        }, "aircast-mdns").apply { isDaemon = true }.start()
    }

    /** Tear down the responder and release the multicast lock (off-thread; close blocks). */
    fun stop() {
        val j = jmdns; jmdns = null
        val l = lock; lock = null
        if (j == null && l == null) return
        Thread({
            try { j?.close() } catch (_: Exception) {}
            try { if (l?.isHeld == true) l.release() } catch (_: Exception) {}
        }, "aircast-mdns-stop").apply { isDaemon = true }.start()
    }

    companion object {
        const val HOST = "aircast"
    }
}
