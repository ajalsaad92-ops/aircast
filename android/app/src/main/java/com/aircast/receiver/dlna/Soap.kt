package com.aircast.receiver.dlna

import java.util.Locale

/**
 * Minimal SOAP + DIDL-Lite handling.
 *
 * A full XML parser buys nothing here: UPnP control messages are flat, the argument
 * names are fixed by the spec, and controllers differ only in which namespace prefix
 * they use — which a namespace-agnostic pattern absorbs.
 */
object Soap {

    data class Action(val serviceType: String, val name: String)

    /** `SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#Play"` */
    fun parseSoapAction(header: String?): Action? {
        val raw = header?.trim()?.trim('"') ?: return null
        val hash = raw.lastIndexOf('#')
        if (hash <= 0) return null
        return Action(raw.substring(0, hash), raw.substring(hash + 1))
    }

    private val cache = HashMap<String, Regex>()

    private fun tagRegex(name: String): Regex = synchronized(cache) {
        cache.getOrPut(name) {
            Regex(
                "<(?:[A-Za-z0-9_.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?$name>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
        }
    }

    /** Reads a single SOAP argument, un-escaping XML entities. */
    fun arg(body: String, name: String): String? =
        tagRegex(name).find(body)?.groupValues?.get(1)?.let { Upnp.unescape(it).trim() }

    fun argInt(body: String, name: String, fallback: Int = 0): Int =
        arg(body, name)?.trim()?.toIntOrNull() ?: fallback

    fun response(action: String, serviceType: String, args: List<Pair<String, String>>): String {
        val inner = args.joinToString("") { (k, v) -> "<$k>${Upnp.escape(v)}</$k>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="$serviceType">$inner</u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    fun fault(errorCode: Int, description: String): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$errorCode</errorCode>
          <errorDescription>${Upnp.escape(description)}</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""

    // ---- DIDL-Lite ----------------------------------------------------------

    data class Didl(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtUri: String = "",
        val upnpClass: String = "",
        val protocolInfo: String = "",
        val durationMs: Long = 0,
    )

    /**
     * Controllers send `CurrentURIMetaData` as an XML-escaped DIDL-Lite document, so it
     * has to be un-escaped once before its own tags become visible.
     */
    fun parseDidl(metadataRaw: String?): Didl {
        if (metadataRaw.isNullOrBlank()) return Didl()
        val xml = if (metadataRaw.contains("&lt;")) Upnp.unescape(metadataRaw) else metadataRaw
        val duration = Regex("duration\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
        val protocolInfo = Regex("protocolInfo\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1) ?: ""
        return Didl(
            title = arg(xml, "title") ?: "",
            artist = arg(xml, "artist") ?: arg(xml, "creator") ?: "",
            album = arg(xml, "album") ?: "",
            albumArtUri = arg(xml, "albumArtURI") ?: "",
            upnpClass = arg(xml, "class") ?: "",
            protocolInfo = protocolInfo,
            durationMs = duration?.let { com.aircast.receiver.player.Playback.parseDuration(it) } ?: 0,
        )
    }

    /** Builds the DIDL we hand back in `GetPositionInfo`, so controllers can render a now-playing card. */
    fun buildDidl(
        title: String,
        artist: String,
        album: String,
        albumArtUri: String,
        uri: String,
        upnpClass: String,
        durationMs: Long,
    ): String {
        val duration = com.aircast.receiver.player.Playback.formatDuration(durationMs)
        val art = if (albumArtUri.isBlank()) "" else
            "<upnp:albumArtURI>${Upnp.escape(albumArtUri)}</upnp:albumArtURI>"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${Upnp.escape(title)}</dc:title><dc:creator>${Upnp.escape(artist)}</dc:creator><upnp:artist>${Upnp.escape(artist)}</upnp:artist><upnp:album>${Upnp.escape(album)}</upnp:album>$art<upnp:class>${Upnp.escape(upnpClass)}</upnp:class><res duration="$duration" protocolInfo="http-get:*:*:*">${Upnp.escape(uri)}</res></item></DIDL-Lite>"""
    }

    /** Best-effort media-kind guess from the DIDL class, then the file extension. */
    fun guessKind(upnpClass: String, url: String): com.aircast.receiver.player.Playback.Kind {
        val c = upnpClass.lowercase(Locale.US)
        when {
            c.contains("videoitem") -> return com.aircast.receiver.player.Playback.Kind.VIDEO
            c.contains("audioitem") || c.contains("musictrack") ->
                return com.aircast.receiver.player.Playback.Kind.AUDIO
            c.contains("imageitem") || c.contains("photo") ->
                return com.aircast.receiver.player.Playback.Kind.IMAGE
        }
        val path = url.substringBefore('?').lowercase(Locale.US)
        return when {
            IMAGE_EXT.any { path.endsWith(it) } -> com.aircast.receiver.player.Playback.Kind.IMAGE
            AUDIO_EXT.any { path.endsWith(it) } -> com.aircast.receiver.player.Playback.Kind.AUDIO
            else -> com.aircast.receiver.player.Playback.Kind.VIDEO
        }
    }

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")
    private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma")
}
