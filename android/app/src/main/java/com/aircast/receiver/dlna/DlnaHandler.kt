package com.aircast.receiver.dlna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Sessions
import com.aircast.receiver.player.Playback
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Serves the UPnP MediaRenderer surface: description, SCPDs, SOAP control and GENA. */
class DlnaHandler(private val context: Context) {

    private val prefs = Prefs.get(context)
    private val iconCache = ConcurrentHashMap<Int, ByteArray>()

    fun handle(req: HttpRequest): HttpResponse? {
        val path = req.path
        return when {
            path == "/description.xml" || path == "/dmr.xml" -> description(req)
            path.startsWith("/scpd/") -> scpd(path)
            path.startsWith("/control/") -> control(req)
            path.startsWith("/event/") -> event(req)
            path.startsWith("/icon/") -> icon(path)
            else -> null
        }
    }

    // ---- documents ----------------------------------------------------------

    private fun description(req: HttpRequest): HttpResponse {
        val udn = Net.uuid(context)
        val xml = Upnp.description(
            friendlyName = prefs.deviceName,
            udn = udn,
            baseUrl = "http://${req.localIp}:${req.localPort}",
            serial = udn.takeLast(12),
            projectUrl = PROJECT_URL,
        )
        return HttpResponse.xml(xml).also {
            it.headers["Content-Language"] = "en"
        }
    }

    private fun scpd(path: String): HttpResponse = when (path.substringAfterLast('/')) {
        "AVTransport.xml" -> HttpResponse.xml(Upnp.AV_TRANSPORT_SCPD)
        "RenderingControl.xml" -> HttpResponse.xml(Upnp.RENDERING_CONTROL_SCPD)
        "ConnectionManager.xml" -> HttpResponse.xml(Upnp.CONNECTION_MANAGER_SCPD)
        else -> HttpResponse.notFound()
    }

    // ---- SOAP control -------------------------------------------------------

    private fun control(req: HttpRequest): HttpResponse {
        if (req.method == "OPTIONS") return HttpResponse.empty(200)
        if (req.method != "POST") return HttpResponse.text("Method Not Allowed", 405)

        val action = Soap.parseSoapAction(req.header("soapaction"))
            ?: return HttpResponse.xml(Soap.fault(401, "Invalid Action"), 500)
        val body = req.bodyText()
        Sessions.touch("dlna", req.remoteIp)
        Logger.i("dlna", "${action.name} from ${req.remoteIp}")

        return try {
            when {
                req.path.endsWith("AVTransport") -> avTransport(action.name, body, req)
                req.path.endsWith("RenderingControl") -> renderingControl(action.name, body)
                req.path.endsWith("ConnectionManager") -> connectionManager(action.name)
                else -> HttpResponse.xml(Soap.fault(401, "Invalid Service"), 500)
            }
        } catch (e: Exception) {
            Logger.e("dlna", "${action.name} failed: ${e.message}")
            HttpResponse.xml(Soap.fault(501, "Action Failed"), 500)
        }
    }

    /** Pulls a helper `subtitle=` parameter off a media URL when present. */
    private fun extractSubtitleParam(uri: String): String {
        return try {
            val q = uri.substringAfter('?', "").split('&').firstOrNull { it.startsWith("subtitle=") }
            java.net.URLDecoder.decode(q?.substringAfter("subtitle=").orEmpty(), "UTF-8")
        } catch (_: Exception) { "" }
    }

    private fun avTransport(action: String, body: String, req: HttpRequest): HttpResponse {
        val svc = Upnp.SVC_AVTRANSPORT
        when (action) {
            "SetAVTransportURI" -> {
                val uri = Soap.arg(body, "CurrentURI").orEmpty()
                val meta = Soap.arg(body, "CurrentURIMetaData").orEmpty()
                if (uri.isBlank()) return HttpResponse.xml(Soap.fault(402, "Invalid Args"), 500)
                val didl = Soap.parseDidl(meta)
                Playback.metadata = meta
                // DLNA senders can't natively carry subtitles, so a helper URL parameter
                // (`?subtitle=/subtitle/token.vtt`) is accepted on the media URI itself.
                val subtitleUrl = extractSubtitleParam(uri)
                Playback.open(
                    context,
                    Playback.Request(
                        url = uri,
                        kind = Soap.guessKind(didl.upnpClass, uri),
                        title = didl.title.ifBlank { uri.substringAfterLast('/').substringBefore('?') },
                        artist = didl.artist,
                        album = didl.album,
                        artUri = didl.albumArtUri,
                        source = "dlna",
                        senderName = req.header("user-agent")?.take(40).orEmpty(),
                        senderIp = req.remoteIp,
                        subtitleUrl = subtitleUrl,
                    ),
                )
                return ok(action, svc)
            }

            "SetNextAVTransportURI" -> {
                Playback.nextUri = Soap.arg(body, "NextURI").orEmpty()
                Playback.nextMetadata = Soap.arg(body, "NextURIMetaData").orEmpty()
                return ok(action, svc)
            }

            "Play" -> {
                val controller = Playback.controller
                if (controller != null) {
                    controller.play()
                } else if (Playback.uri.isNotBlank()) {
                    // The controller sent Play after we lost the player (user pressed back).
                    Playback.open(
                        context,
                        Playback.Request(
                            url = Playback.uri,
                            kind = Playback.kind,
                            title = Playback.title,
                            artist = Playback.artist,
                            album = Playback.album,
                            artUri = Playback.artUri,
                            startPositionMs = Playback.positionMs(),
                            source = "dlna",
                            senderIp = req.remoteIp,
                        ),
                    )
                }
                return ok(action, svc)
            }

            "Pause" -> { Playback.controller?.pause(); return ok(action, svc) }

            "Stop" -> { Playback.controller?.stopPlayback() ?: Playback.setState(Playback.State.STOPPED); return ok(action, svc) }

            "Seek" -> {
                val unit = Soap.arg(body, "Unit").orEmpty().uppercase(Locale.US)
                val target = Soap.arg(body, "Target").orEmpty()
                val ms = when (unit) {
                    "REL_TIME", "ABS_TIME" -> Playback.parseDuration(target)
                    "X_DLNA_REL_BYTE", "TRACK_NR" -> -1
                    else -> Playback.parseDuration(target)
                }
                if (ms >= 0) Playback.controller?.seekTo(ms)
                return ok(action, svc)
            }

            "Next", "Previous" -> {
                if (action == "Next" && Playback.nextUri.isNotBlank()) {
                    val didl = Soap.parseDidl(Playback.nextMetadata)
                    val next = Playback.nextUri
                    Playback.nextUri = ""
                    Playback.open(
                        context,
                        Playback.Request(
                            url = next,
                            kind = Soap.guessKind(didl.upnpClass, next),
                            title = didl.title,
                            artist = didl.artist,
                            album = didl.album,
                            artUri = didl.albumArtUri,
                            source = "dlna",
                            senderIp = req.remoteIp,
                        ),
                    )
                }
                return ok(action, svc)
            }

            "GetTransportInfo" -> return HttpResponse.xml(
                Soap.response(
                    action, svc,
                    listOf(
                        "CurrentTransportState" to Playback.state.upnp,
                        "CurrentTransportStatus" to "OK",
                        "CurrentSpeed" to "1",
                    ),
                ),
            )

            "GetPositionInfo" -> {
                val duration = Playback.formatDuration(Playback.durationMs())
                val position = Playback.formatDuration(Playback.positionMs())
                val metadata = Playback.metadata.ifBlank {
                    Soap.buildDidl(
                        Playback.title, Playback.artist, Playback.album, Playback.artUri,
                        Playback.uri, upnpClassFor(Playback.kind), Playback.durationMs(),
                    )
                }
                return HttpResponse.xml(
                    Soap.response(
                        action, svc,
                        listOf(
                            "Track" to "1",
                            "TrackDuration" to duration,
                            "TrackMetaData" to metadata,
                            "TrackURI" to Playback.uri,
                            "RelTime" to position,
                            "AbsTime" to position,
                            "RelCount" to "2147483647",
                            "AbsCount" to "2147483647",
                        ),
                    ),
                )
            }

            "GetMediaInfo" -> return HttpResponse.xml(
                Soap.response(
                    action, svc,
                    listOf(
                        "NrTracks" to if (Playback.uri.isBlank()) "0" else "1",
                        "MediaDuration" to Playback.formatDuration(Playback.durationMs()),
                        "CurrentURI" to Playback.uri,
                        "CurrentURIMetaData" to Playback.metadata,
                        "NextURI" to Playback.nextUri,
                        "NextURIMetaData" to Playback.nextMetadata,
                        "PlayMedium" to "NETWORK",
                        "RecordMedium" to "NOT_IMPLEMENTED",
                        "WriteStatus" to "NOT_IMPLEMENTED",
                    ),
                ),
            )

            "GetDeviceCapabilities" -> return HttpResponse.xml(
                Soap.response(
                    action, svc,
                    listOf(
                        "PlayMedia" to "NETWORK,HDD,UNKNOWN",
                        "RecMedia" to "NOT_IMPLEMENTED",
                        "RecQualityModes" to "NOT_IMPLEMENTED",
                    ),
                ),
            )

            "GetTransportSettings" -> return HttpResponse.xml(
                Soap.response(
                    action, svc,
                    listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"),
                ),
            )

            "GetCurrentTransportActions" -> return HttpResponse.xml(
                Soap.response(action, svc, listOf("Actions" to "Play,Pause,Stop,Seek,X_DLNA_SeekTime")),
            )

            "SetPlayMode" -> return ok(action, svc)
        }
        return HttpResponse.xml(Soap.fault(401, "Invalid Action"), 500)
    }

    private fun renderingControl(action: String, body: String): HttpResponse {
        val svc = Upnp.SVC_RENDERING
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        when (action) {
            "GetVolume" -> {
                val current = audio?.let {
                    val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    (it.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max
                } ?: Playback.volume
                Playback.volume = current
                return HttpResponse.xml(
                    Soap.response(action, svc, listOf("CurrentVolume" to current.toString())),
                )
            }

            "SetVolume" -> {
                val desired = Soap.argInt(body, "DesiredVolume", Playback.volume).coerceIn(0, 100)
                Playback.volume = desired
                Playback.controller?.setVolumePercent(desired) ?: audio?.let {
                    val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    it.setStreamVolume(AudioManager.STREAM_MUSIC, (desired * max) / 100, 0)
                }
                Playback.broadcast()
                return ok(action, svc)
            }

            "GetMute" -> return HttpResponse.xml(
                Soap.response(action, svc, listOf("CurrentMute" to if (Playback.muted) "1" else "0")),
            )

            "SetMute" -> {
                val raw = Soap.arg(body, "DesiredMute").orEmpty()
                val muted = raw == "1" || raw.equals("true", true)
                Playback.muted = muted
                Playback.controller?.setMuted(muted)
                Playback.broadcast()
                return ok(action, svc)
            }

            "ListPresets" -> return HttpResponse.xml(
                Soap.response(action, svc, listOf("CurrentPresetNameList" to "FactoryDefaults")),
            )

            "SelectPreset" -> return ok(action, svc)
        }
        return HttpResponse.xml(Soap.fault(401, "Invalid Action"), 500)
    }

    private fun connectionManager(action: String): HttpResponse {
        val svc = Upnp.SVC_CONNECTION
        return when (action) {
            "GetProtocolInfo" -> HttpResponse.xml(
                Soap.response(action, svc, listOf("Source" to "", "Sink" to Upnp.PROTOCOL_INFO_SINK)),
            )
            "GetCurrentConnectionIDs" -> HttpResponse.xml(
                Soap.response(action, svc, listOf("ConnectionIDs" to "0")),
            )
            "GetCurrentConnectionInfo" -> HttpResponse.xml(
                Soap.response(
                    action, svc,
                    listOf(
                        "RcsID" to "0",
                        "AVTransportID" to "0",
                        "ProtocolInfo" to "",
                        "PeerConnectionManager" to "",
                        "PeerConnectionID" to "-1",
                        "Direction" to "Input",
                        "Status" to "OK",
                    ),
                ),
            )
            else -> HttpResponse.xml(Soap.fault(401, "Invalid Action"), 500)
        }
    }

    private fun ok(action: String, service: String) =
        HttpResponse.xml(Soap.response(action, service, emptyList()))

    // ---- GENA ---------------------------------------------------------------

    private fun event(req: HttpRequest): HttpResponse {
        val service = when {
            req.path.endsWith("AVTransport") -> Upnp.SVC_AVTRANSPORT
            req.path.endsWith("RenderingControl") -> Upnp.SVC_RENDERING
            req.path.endsWith("ConnectionManager") -> Upnp.SVC_CONNECTION
            else -> return HttpResponse.notFound()
        }

        return when (req.method) {
            "SUBSCRIBE" -> {
                val existingSid = req.header("sid")
                val result = if (existingSid != null) {
                    Gena.renew(existingSid, req.header("timeout"))
                } else {
                    Gena.subscribe(service, req.header("callback"), req.header("timeout"))
                }
                if (result == null) return HttpResponse.text("Precondition Failed", 412)
                val (sid, timeout) = result
                if (existingSid == null) Gena.sendInitial(sid, initialStateFor(service))
                // No explicit Content-Length here: the writer already emits one, and a
                // duplicated header makes strict UPnP controllers drop the subscription.
                HttpResponse.empty(200).also {
                    it.headers["SID"] = sid
                    it.headers["TIMEOUT"] = "Second-$timeout"
                }
            }

            "UNSUBSCRIBE" -> {
                val sid = req.header("sid") ?: return HttpResponse.text("Precondition Failed", 412)
                Gena.unsubscribe(sid)
                HttpResponse.empty(200)
            }

            else -> HttpResponse.text("Method Not Allowed", 405)
        }
    }

    private fun initialStateFor(service: String): String = when (service) {
        Upnp.SVC_AVTRANSPORT -> Gena.propertySet(
            "LastChange" to Gena.lastChange(
                Gena.NS_AVT,
                listOf(
                    "TransportState" to Playback.state.upnp,
                    "TransportStatus" to "OK",
                    "CurrentTrackURI" to Playback.uri,
                    "CurrentTrackDuration" to Playback.formatDuration(Playback.durationMs()),
                    "CurrentTransportActions" to "Play,Pause,Stop,Seek",
                ),
            ),
        )
        Upnp.SVC_RENDERING -> Gena.propertySet(
            "LastChange" to Gena.lastChange(
                Gena.NS_RCS,
                listOf(
                    "Volume" to Playback.volume.toString(),
                    "Mute" to if (Playback.muted) "1" else "0",
                ),
            ),
        )
        else -> Gena.propertySet(
            "SinkProtocolInfo" to Upnp.PROTOCOL_INFO_SINK,
            "SourceProtocolInfo" to "",
            "CurrentConnectionIDs" to "0",
        )
    }

    /** Called by the service whenever transport state moves. */
    fun pushTransportState() {
        Gena.notifyService(
            Upnp.SVC_AVTRANSPORT,
            Gena.propertySet(
                "LastChange" to Gena.lastChange(
                    Gena.NS_AVT,
                    listOf(
                        "TransportState" to Playback.state.upnp,
                        "TransportStatus" to "OK",
                        "CurrentTrackURI" to Playback.uri,
                        "CurrentTrackDuration" to Playback.formatDuration(Playback.durationMs()),
                        "CurrentMediaDuration" to Playback.formatDuration(Playback.durationMs()),
                        "CurrentTransportActions" to "Play,Pause,Stop,Seek",
                    ),
                ),
            ),
        )
    }

    // ---- icons --------------------------------------------------------------

    /**
     * Rendered on demand instead of shipping PNG assets — controllers ask for three
     * different sizes and a vector-drawn icon stays crisp at all of them.
     */
    private fun icon(path: String): HttpResponse {
        val size = path.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: 120
        val clamped = size.coerceIn(16, 512)
        val png = iconCache.getOrPut(clamped) { renderIcon(clamped) }
        return HttpResponse.bytes(png, "image/png").also {
            it.headers["Cache-Control"] = "public, max-age=86400"
        }
    }

    private fun renderIcon(size: Int): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = size.toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, s, s,
                intArrayOf(0xFF1B2A5B.toInt(), 0xFF0EA5E9.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * 0.22f, s * 0.22f, bg)

        // Three broadcast arcs plus a dot — the universal "casting" glyph.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeWidth = s * 0.075f
        }
        val cx = s * 0.30f
        val cy = s * 0.72f
        for (i in 1..3) {
            val r = s * (0.16f * i)
            val path = Path().apply {
                addArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 90f)
            }
            canvas.drawPath(path, stroke)
        }
        canvas.drawCircle(cx, cy, s * 0.055f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        })

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun upnpClassFor(kind: Playback.Kind) = when (kind) {
        Playback.Kind.VIDEO -> "object.item.videoItem"
        Playback.Kind.AUDIO -> "object.item.audioItem.musicTrack"
        Playback.Kind.IMAGE -> "object.item.imageItem.photo"
    }

    companion object {
        const val PROJECT_URL = "https://github.com/ajalsaad92-ops/aircast-receiver"
    }
}
