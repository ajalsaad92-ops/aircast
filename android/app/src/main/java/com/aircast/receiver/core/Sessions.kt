package com.aircast.receiver.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is currently talking to the receiver. Keyed by "protocol@ip" so a phone that
 * casts over DLNA and then mirrors shows up as two distinct sessions, which is what
 * the user sees on screen anyway.
 */
object Sessions {

    data class Session(
        val id: String,
        val protocol: String,
        val ip: String,
        var name: String,
        val startedAt: Long,
        var lastSeen: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("protocol", protocol)
            .put("ip", ip)
            .put("name", name)
            .put("startedAt", startedAt)
            .put("lastSeen", lastSeen)
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun touch(protocol: String, ip: String, name: String = ""): Session {
        val id = "$protocol@$ip"
        val now = System.currentTimeMillis()
        val existing = sessions[id]
        if (existing != null) {
            existing.lastSeen = now
            if (name.isNotEmpty()) existing.name = name
            return existing
        }
        val created = Session(id, protocol, ip, name.ifEmpty { ip }, now, now)
        sessions[id] = created
        Logger.i("session", "connected: $protocol from $ip${if (name.isNotEmpty()) " ($name)" else ""}")
        Events.emit("clientConnected", created.toJson())
        Events.emit("sessionsChanged", JSONObject().put("sessions", toJsonArray()))
        return created
    }

    fun end(protocol: String, ip: String) {
        val removed = sessions.remove("$protocol@$ip") ?: return
        Logger.i("session", "disconnected: $protocol from $ip")
        Events.emit("clientDisconnected", removed.toJson())
        Events.emit("sessionsChanged", JSONObject().put("sessions", toJsonArray()))
    }

    /** Drops sessions that have not been seen for [maxAgeMs]; called from the service tick. */
    fun sweep(maxAgeMs: Long = 120_000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var changed = false
        for ((key, s) in sessions) {
            if (s.lastSeen < cutoff) {
                sessions.remove(key)
                changed = true
                Events.emit("clientDisconnected", s.toJson())
            }
        }
        if (changed) Events.emit("sessionsChanged", JSONObject().put("sessions", toJsonArray()))
    }

    fun clear() {
        if (sessions.isEmpty()) return
        sessions.clear()
        Events.emit("sessionsChanged", JSONObject().put("sessions", toJsonArray()))
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (s in sessions.values.sortedBy { it.startedAt }) arr.put(s.toJson())
        return arr
    }

    fun count(): Int = sessions.size
}
