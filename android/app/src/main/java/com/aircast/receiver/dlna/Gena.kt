package com.aircast.receiver.dlna

import com.aircast.receiver.core.Logger
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * GENA — the UPnP eventing half.
 *
 * Skipping this still yields a renderer that *plays*, but controllers that rely on
 * pushed `LastChange` events (Windows "Cast to device", Samsung/LG phone galleries)
 * show a frozen progress bar and often give up after a few seconds. So it is worth
 * the ~150 lines.
 */
object Gena {

    private class Subscription(
        val sid: String,
        val service: String,
        val callbacks: List<String>,
        @Volatile var expiresAt: Long,
        val seq: AtomicInteger = AtomicInteger(0),
    )

    private val subs = ConcurrentHashMap<String, Subscription>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "gena-notify").apply { isDaemon = true } }

    /** `CALLBACK: <http://ip:port/a><http://ip:port/b>` */
    private fun parseCallbacks(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return Regex("<([^>]+)>").findAll(header).map { it.groupValues[1] }.toList()
    }

    private fun parseTimeout(header: String?): Long {
        val seconds = header?.substringAfter("Second-", "")?.trim()?.toLongOrNull() ?: 1800L
        return seconds.coerceIn(60L, 3600L)
    }

    /** @return Pair(SID, timeoutSeconds) or null when the request is not a valid SUBSCRIBE. */
    fun subscribe(service: String, callbackHeader: String?, timeoutHeader: String?): Pair<String, Long>? {
        val callbacks = parseCallbacks(callbackHeader)
        if (callbacks.isEmpty()) return null
        val timeout = parseTimeout(timeoutHeader)
        val sid = "uuid:" + UUID.randomUUID().toString()
        subs[sid] = Subscription(sid, service, callbacks, System.currentTimeMillis() + timeout * 1000)
        Logger.i("gena", "subscribe $service -> ${callbacks.first()} (sid=${sid.takeLast(8)})")
        return sid to timeout
    }

    fun renew(sid: String, timeoutHeader: String?): Pair<String, Long>? {
        val sub = subs[sid] ?: return null
        val timeout = parseTimeout(timeoutHeader)
        sub.expiresAt = System.currentTimeMillis() + timeout * 1000
        return sid to timeout
    }

    fun unsubscribe(sid: String): Boolean = subs.remove(sid) != null

    fun clear() = subs.clear()

    fun sweep() {
        val now = System.currentTimeMillis()
        subs.entries.removeAll { it.value.expiresAt < now }
    }

    /** Sends the initial state burst a controller expects right after it subscribes. */
    fun sendInitial(sid: String, propertyXml: String) {
        val sub = subs[sid] ?: return
        io.execute { deliver(sub, propertyXml) }
    }

    fun notifyService(service: String, propertyXml: String) {
        val targets = subs.values.filter { it.service == service }
        if (targets.isEmpty()) return
        io.execute { for (sub in targets) deliver(sub, propertyXml) }
    }

    fun propertySet(vararg properties: Pair<String, String>): String {
        val body = properties.joinToString("") { (name, value) ->
            "<e:property><$name>${Upnp.escape(value)}</$name></e:property>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">$body</e:propertyset>"""
    }

    /** AVTransport / RenderingControl both wrap their state in a `LastChange` document. */
    fun lastChange(namespace: String, vars: List<Pair<String, String>>): String {
        val inner = vars.joinToString("") { (k, v) -> "<$k val=\"${Upnp.escape(v)}\"/>" }
        return """<Event xmlns="$namespace"><InstanceID val="0">$inner</InstanceID></Event>"""
    }

    const val NS_AVT = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val NS_RCS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    private fun deliver(sub: Subscription, propertyXml: String) {
        val seq = sub.seq.getAndIncrement()
        for (callback in sub.callbacks) {
            try {
                val uri = URI(callback)
                val host = uri.host ?: continue
                val port = if (uri.port > 0) uri.port else 80
                val path = (uri.rawPath ?: "/").ifEmpty { "/" } +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val payload = propertyXml.toByteArray(Charsets.UTF_8)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 4000)
                    socket.soTimeout = 4000
                    val out: OutputStream = socket.getOutputStream()
                    val head = buildString {
                        append("NOTIFY $path HTTP/1.1\r\n")
                        append("HOST: $host:$port\r\n")
                        append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                        append("CONTENT-LENGTH: ${payload.size}\r\n")
                        append("NT: upnp:event\r\n")
                        append("NTS: upnp:propchange\r\n")
                        append("SID: ${sub.sid}\r\n")
                        append("SEQ: $seq\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(payload)
                    out.flush()
                    // Drain the acknowledgement so the peer does not see a reset.
                    socket.getInputStream().read()
                }
                return
            } catch (e: Exception) {
                Logger.w("gena", "notify to $callback failed: ${e.message}")
            }
        }
    }
}
