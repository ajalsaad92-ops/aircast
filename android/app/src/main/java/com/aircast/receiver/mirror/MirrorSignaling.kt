package com.aircast.receiver.mirror

import com.aircast.receiver.core.Events
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Sessions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The signalling half of LAN screen mirroring.
 *
 * Both ends of the peer connection are browsers: the sender is Chrome/Edge/Safari on
 * the user's laptop or phone, the receiver is this app's own WebView. That removes any
 * need for a native WebRTC stack — all that is missing is a way to swap SDP and ICE,
 * which is what this object is.
 *
 * Signalling rides on plain request/response polling rather than WebSockets. On a LAN
 * the exchange is four short messages and finishes in well under a second, so the
 * complexity of a socket upgrade path buys nothing.
 */
object MirrorSignaling {

    class Peer(
        val id: String,
        val ip: String,
        var name: String,
        val createdAt: Long,
    ) {
        @Volatile var offerSdp: String? = null
        @Volatile var answerSdp: String? = null
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        @Volatile var closed: Boolean = false
        val senderCandidates: MutableList<String> = Collections.synchronizedList(ArrayList())
        val receiverCandidates: MutableList<String> = Collections.synchronizedList(ArrayList())

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("ip", ip)
            .put("name", name)
            .put("createdAt", createdAt)
            .put("connected", answerSdp != null && !closed)
    }

    private val peers = ConcurrentHashMap<String, Peer>()

    fun peer(id: String): Peer? = peers[id]

    /** Called from the HTTP layer when a sender posts its offer. */
    fun offer(id: String, ip: String, name: String, sdp: String): Peer {
        val peer = peers.getOrPut(id) { Peer(id, ip, name, System.currentTimeMillis()) }
        peer.name = name.ifBlank { peer.name }
        peer.offerSdp = sdp
        peer.answerSdp = null
        peer.closed = false
        peer.lastSeen = System.currentTimeMillis()
        peer.receiverCandidates.clear()
        peer.senderCandidates.clear()
        Logger.i("mirror", "offer from $name ($ip)")
        Sessions.touch("mirror", ip, name)
        Events.emit(
            "mirrorOffer",
            JSONObject().put("id", id).put("ip", ip).put("name", peer.name).put("sdp", sdp),
        )
        return peer
    }

    /** Called from the plugin once the WebView has produced an answer. */
    fun setAnswer(id: String, sdp: String): Boolean {
        val peer = peers[id] ?: return false
        peer.answerSdp = sdp
        peer.lastSeen = System.currentTimeMillis()
        Logger.i("mirror", "answer ready for ${peer.name}")
        Events.emit("mirrorStateChanged", JSONObject().put("peers", peersJson()))
        return true
    }

    fun addSenderCandidate(id: String, candidate: String) {
        val peer = peers[id] ?: return
        peer.senderCandidates.add(candidate)
        peer.lastSeen = System.currentTimeMillis()
        Events.emit(
            "mirrorCandidate",
            JSONObject().put("id", id).put("candidate", JSONObject(candidate)),
        )
    }

    fun addReceiverCandidate(id: String, candidate: String) {
        val peer = peers[id] ?: return
        peer.receiverCandidates.add(candidate)
    }

    fun candidatesSince(list: List<String>, since: Int): JSONArray {
        val arr = JSONArray()
        val snapshot = synchronized(list) { ArrayList(list) }
        for (i in since until snapshot.size) arr.put(JSONObject(snapshot[i]))
        return arr
    }

    fun close(id: String, reason: String = "closed") {
        val peer = peers.remove(id) ?: return
        peer.closed = true
        Logger.i("mirror", "session with ${peer.name} ended ($reason)")
        Sessions.end("mirror", peer.ip)
        Events.emit("mirrorStopped", JSONObject().put("id", id).put("reason", reason))
        Events.emit("mirrorStateChanged", JSONObject().put("peers", peersJson()))
    }

    fun closeAll() {
        for (id in peers.keys.toList()) close(id, "receiver stopped")
    }

    /** Drops peers that stopped polling — a laptop that closed the tab never says goodbye. */
    fun sweep(maxIdleMs: Long = 45_000) {
        val cutoff = System.currentTimeMillis() - maxIdleMs
        for ((id, peer) in peers) {
            if (peer.lastSeen < cutoff) close(id, "timed out")
        }
    }

    fun touch(id: String) {
        peers[id]?.lastSeen = System.currentTimeMillis()
    }

    fun activeCount(): Int = peers.values.count { it.answerSdp != null && !it.closed }

    fun peersJson(): JSONArray {
        val arr = JSONArray()
        for (p in peers.values.sortedBy { it.createdAt }) arr.put(p.toJson())
        return arr
    }

    /**
     * Offers that arrived but have not yet been answered by the WebView. The WebView polls
     * this after it (re)registers its listeners so an offer that landed during the gap —
     * classically the very first one, before the JS listener attached — is still picked up
     * instead of being silently lost (the "have to restart the cast" symptom).
     */
    fun pendingOffers(): JSONArray {
        val arr = JSONArray()
        for (p in peers.values.sortedBy { it.createdAt }) {
            val sdp = p.offerSdp
            if (sdp != null && p.answerSdp == null && !p.closed) {
                arr.put(
                    JSONObject().put("id", p.id).put("ip", p.ip).put("name", p.name).put("sdp", sdp),
                )
            }
        }
        return arr
    }
}
