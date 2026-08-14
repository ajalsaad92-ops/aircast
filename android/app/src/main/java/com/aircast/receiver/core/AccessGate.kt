package com.aircast.receiver.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Connection access control — the receiver-side answer to AirScreen's
 * `setting_airPlay_security` (on-screen code / password) and
 * `setting_cast_security` (accept-or-reject dialog with "always trust").
 *
 * There are two independent layers:
 *
 * 1. **AirPlay gate** — every new sender that wants to cast media over AirPlay
 *    must present a secret. In `code` mode the secret is a 4-digit code shown on
 *    this device's screen (`airplayCode`); in `password` mode it is the PIN the
 *    user configured in settings; in `off` mode nothing is checked.
 *
 * 2. **Cast gate** — every new Cast/V2 socket is held in a pending state until
 *    the user accepts it in the UI. Acceptance can be one-time or permanent
 *    ("always trust"), and the UI surfaces a pending-connection event so a
 *    dialog can be shown even when the app is closed (via the notification).
 *
 * Both layers consult the same shared `prefs` instance, and both can be driven
 * from the React UI because every state change emits a `securityChanged` event.
 */
object AccessGate {
    private val random = SecureRandom()
    private val pendingByPeer = ConcurrentHashMap<String, PendingConnection>()
    @Volatile private var appContext: Context? = null

    /** The activity / service must register its context once so the gate can read settings. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class PendingConnection(
        val protocol: String,
        val ip: String,
        val name: String,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("protocol", protocol)
            .put("ip", ip)
            .put("name", name)
            .put("createdAt", createdAt)
    }

    // ---- shared, human-readable airplay code ----------------------------------
    @Volatile private var code: String = ""
    @Volatile private var codeExpiresAt: Long = 0L
    private const val CODE_LIFETIME_MS = 10 * 60 * 1000L

    /** A stable 4-digit code, refreshed on demand and valid for [CODE_LIFETIME_MS]. */
    fun currentAirPlayCode(refresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (refresh || code.isEmpty() || now > codeExpiresAt) {
            code = "%04d".format(random.nextInt(10000))
            codeExpiresAt = now + CODE_LIFETIME_MS
        }
        return code
    }

    // ---- helpers ----------------------------------------------------------------

    private fun prefs(): Prefs? = appContext?.let { Prefs.get(it) }

    // ---- AirPlay side -----------------------------------------------------------

    /** True when the sender's secret satisfies the configured AirPlay gate. */
    fun airplayAuthorized(secret: String?): Boolean {
        val prefs = prefs() ?: return true
        when (prefs.airplaySecurityMode) {
            "password" -> {
                val pin = prefs.pinCode.orEmpty()
                return pin.isEmpty() || secret == pin
            }
            "code" -> {
                val pin = prefs.pinCode.orEmpty()
                return (pin.isNotEmpty() && secret == pin) ||
                        secret == currentAirPlayCode()
            }
            else -> return true
        }
    }

    /**
     * True if there is an AirPlay gate at all and no bypass secret was given —
     * used by the UI to decide whether to show the on-screen code overlay.
     */
    fun needsAirPlayCode(): Boolean {
        val prefs = prefs() ?: return false
        return prefs.airplaySecurityMode == "code" && prefs.pinCode.isNullOrEmpty()
    }

    // ---- Cast side --------------------------------------------------------------

    /**
     * Called when a brand-new Cast socket arrives. Returns true when the sender
     * may be served right away (no gate, or already trusted), false when the
     * socket should be held until the user accepts or rejects it.
     */
    fun castShouldProceed(peer: String, ip: String, name: String): Boolean {
        val prefs = prefs() ?: return true
        if (prefs.castSecurityMode != "ask") return true
        if (prefs.castTrustedPeers().contains(peer)) return true
        val pending = PendingConnection("cast", ip, name.ifEmpty { ip })
        pendingByPeer[peer] = pending
        Logger.i("security", "pending cast connection from $name ($ip)")
        Events.emit("connectionRequest", pending.toJson())
        Events.emit("pendingConnectionsChanged", pendingConnectionsJson())
        return false
    }

    /** Accept or reject a previously held Cast connection. */
    fun castResolve(peer: String, accept: Boolean, trustAlways: Boolean) {
        val removed = pendingByPeer.remove(peer) ?: return
        val prefs = prefs()
        if (accept) {
            if (trustAlways && prefs != null) {
                val trusted = prefs.castTrustedPeers().toMutableSet()
                trusted.add(peer)
                prefs.castTrustedPeers(trusted)
                Logger.i("security", "trusted peer $peer (name: ${removed.name})")
            }
            Logger.i("security", "accepted connection from ${removed.name} ($peer)")
        } else {
            Logger.i("security", "rejected connection from ${removed.name} ($peer)")
        }
        Events.emit("pendingConnectionsChanged", pendingConnectionsJson())
    }

    fun isPending(peer: String): Boolean = pendingByPeer.containsKey(peer)

    /** Lets the caller annotate a held request with the sender's friendly name. */
    fun updatePendingName(peer: String, name: String) {
        val existing = pendingByPeer[peer] ?: return
        if (name.isBlank()) return
        pendingByPeer[peer] = existing.copy(name = name)
        Events.emit("pendingConnectionsChanged", pendingConnectionsJson())
    }

    fun pendingConnections(): List<PendingConnection> =
        pendingByPeer.values.sortedBy { it.createdAt }

    /** Drops stale pending requests; called from the service tick. */
    fun sweepPending(maxAgeMs: Long = 60_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var changed = false
        for ((peer, p) in pendingByPeer) {
            if (p.createdAt < cutoff) {
                pendingByPeer.remove(peer)
                changed = true
            }
        }
        if (changed) Events.emit("pendingConnectionsChanged", pendingConnectionsJson())
    }

    fun pendingConnectionsJson(): JSONObject = JSONObject().put(
        "pending",
        JSONArray().also { arr -> pendingByPeer.values.forEach { arr.put(it.toJson()) } },
    )

    /** Resets all trust decisions (called from settings when the user wants a clean slate). */
    fun clearTrustedPeers() {
        val prefs = prefs() ?: return
        prefs.castTrustedPeers(emptySet())
        Events.emit("pendingConnectionsChanged", pendingConnectionsJson())
    }
}
