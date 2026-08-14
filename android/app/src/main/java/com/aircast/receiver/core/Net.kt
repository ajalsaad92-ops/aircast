package com.aircast.receiver.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/** Network-shape helpers. Everything the receiver advertises depends on getting the LAN IP right. */
object Net {

    /** Interfaces we never advertise on — VPNs, tethering bridges and the loopback. */
    private val IGNORED_PREFIXES = listOf("lo", "dummy", "tun", "ppp", "p2p", "rmnet")

    /**
     * All usable IPv4 addresses, best first.
     *
     * Ethernet is preferred over Wi-Fi: Android TV boxes are commonly wired, and a box
     * with both up would otherwise advertise whichever interface enumerated first.
     */
    fun localIpv4Addresses(): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.US)
                if (IGNORED_PREFIXES.any { name.startsWith(it) }) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val rank = when {
                        name.startsWith("eth") -> 0
                        name.startsWith("wlan") -> 1
                        else -> 2
                    }
                    out.add(rank to (addr.hostAddress ?: continue))
                }
            }
        } catch (e: Exception) {
            Logger.w("net", "interface scan failed: ${e.message}")
        }
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun primaryIp(): String = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"

    fun primaryInetAddress(): InetAddress? = try {
        InetAddress.getByName(primaryIp())
    } catch (_: Exception) {
        null
    }

    /** The interface carrying [ip], needed to bind SSDP's multicast socket to the right NIC. */
    fun interfaceFor(ip: String): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { nif ->
                nif.inetAddresses.toList().any { it.hostAddress == ip }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun transportName(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "none") ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    /**
     * SSID is only readable with location permission on API 27+, and we deliberately
     * do not ask for location — so this is a nice-to-have that degrades to null.
     */
    @Suppress("DEPRECATION")
    fun ssid(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val info = wm.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            val clean = raw.trim('"')
            if (clean.isEmpty() || clean == "<unknown ssid>" || clean == "0x") null else clean
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A stable 12-hex-digit pseudo-MAC. Real MACs are unreadable since Android 6, but
     * AirPlay/RAOP identifiers must look like one and stay constant across restarts.
     */
    fun deviceId(context: Context): String {
        val prefs = Prefs.get(context)
        prefs.deviceIdHex?.let { return it }
        val seed = (Build.MODEL + Build.DEVICE + Build.BOARD + java.util.UUID.randomUUID())
            .hashCode().toLong() and 0xFFFFFFFFL
        val hex = String.format(Locale.US, "02%010X", seed).take(12)
        prefs.deviceIdHex = hex
        return hex
    }

    fun deviceIdColon(context: Context): String =
        deviceId(context).chunked(2).joinToString(":")

    fun uuid(context: Context): String {
        val prefs = Prefs.get(context)
        prefs.uuid?.let { return it }
        val v = java.util.UUID.randomUUID().toString()
        prefs.uuid = v
        return v
    }

    /**
     * One-shot watcher: fires `networkGone` once when connectivity is lost and
     * `networkBack` once when it returns, mirroring AirScreen's network-loss toast.
     * Registered from the service; call [unregisterWatcher] on stop.
     */
    private var watcherCallback: ConnectivityManager.NetworkCallback? = null
    private var watcherActive = false

    fun registerWatcher(context: Context) {
        if (watcherActive) return
        watcherActive = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Logger.w("net", "active network lost; senders may stall")
                Events.emit("networkGone")
            }
            override fun onAvailable(network: Network) {
                Logger.i("net", "network available again")
                Events.emit("networkBack")
            }
        }
        watcherCallback = cb
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        } catch (e: SecurityException) {
            Logger.w("net", "network callback not available: ${e.message}")
        }
    }

    fun unregisterWatcher(context: Context) {
        if (!watcherActive) return
        watcherActive = false
        watcherCallback?.let { cb ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try {
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
                /* already unregistered */
            }
        }
        watcherCallback = null
    }

    fun defaultDeviceName(): String {
        val model = (Build.MODEL ?: "Android").trim()
        val brand = (Build.BRAND ?: "").trim().replaceFirstChar { it.uppercase(Locale.US) }
        val name = if (model.startsWith(brand, ignoreCase = true) || brand.isEmpty()) model else "$brand $model"
        return "AirCast ($name)"
    }
}
