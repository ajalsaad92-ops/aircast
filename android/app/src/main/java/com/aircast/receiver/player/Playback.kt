package com.aircast.receiver.player

import android.content.Context
import android.content.Intent
import com.aircast.receiver.core.Events
import com.aircast.receiver.core.Logger
import org.json.JSONObject
import java.util.Locale

/**
 * The single playback state shared by every protocol.
 *
 * DLNA and AirPlay are two different remote controls pointed at the same renderer, so
 * they must not each keep their own idea of "what is playing" — a phone that paused over
 * AirPlay and a laptop polling `GetTransportInfo` over DLNA have to agree.
 */
object Playback {

    enum class Kind { VIDEO, AUDIO, IMAGE }

    enum class State(val upnp: String) {
        NO_MEDIA("NO_MEDIA_PRESENT"),
        STOPPED("STOPPED"),
        PLAYING("PLAYING"),
        PAUSED("PAUSED_PLAYBACK"),
        TRANSITIONING("TRANSITIONING"),
    }

    data class Request(
        val url: String,
        val kind: Kind,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artUri: String = "",
        val startPositionMs: Long = 0,
        val source: String = "dlna",
        val senderName: String = "",
        val senderIp: String = "",
        val mimeHint: String = "",
    )

    /** Implemented by [PlayerActivity] while it is on screen. */
    interface Controller {
        fun load(request: Request)
        fun play()
        fun pause()
        fun stopPlayback()
        fun seekTo(positionMs: Long)
        fun setVolumePercent(percent: Int)
        fun setMuted(muted: Boolean)
        fun currentPositionMs(): Long
        fun durationMs(): Long
    }

    @Volatile var controller: Controller? = null

    @Volatile var state: State = State.NO_MEDIA
        private set
    @Volatile var uri: String = ""
    @Volatile var metadata: String = ""
    @Volatile var nextUri: String = ""
    @Volatile var nextMetadata: String = ""
    @Volatile var title: String = ""
    @Volatile var artist: String = ""
    @Volatile var album: String = ""
    @Volatile var artUri: String = ""
    @Volatile var kind: Kind = Kind.VIDEO
    @Volatile var source: String = ""
    @Volatile var senderName: String = ""
    @Volatile var senderIp: String = ""
    @Volatile var volume: Int = 60
    @Volatile var muted: Boolean = false

    /** Fallbacks used when no player is attached (e.g. a controller polls before we start). */
    @Volatile private var lastKnownPosition: Long = 0
    @Volatile private var lastKnownDuration: Long = 0

    /** Listeners that want to mirror state outward (GENA event bursts). */
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    fun addStateListener(l: (State) -> Unit) = stateListeners.add(l)
    fun removeStateListener(l: (State) -> Unit) = stateListeners.remove(l)

    fun positionMs(): Long = controller?.currentPositionMs() ?: lastKnownPosition
    fun durationMs(): Long = controller?.durationMs() ?: lastKnownDuration

    fun cachePosition(position: Long, duration: Long) {
        lastKnownPosition = position
        lastKnownDuration = duration
    }

    fun setState(next: State, notify: Boolean = true) {
        if (state == next) return
        state = next
        Logger.i("playback", "state -> ${next.upnp}")
        for (l in stateListeners) {
            try { l(next) } catch (_: Exception) {}
        }
        if (notify) broadcast()
    }

    fun broadcast() = Events.emit("playbackChanged", toJson())

    /** Hands a new media item to the renderer, launching the player if needed. */
    fun open(context: Context, request: Request) {
        uri = request.url
        title = request.title
        artist = request.artist
        album = request.album
        artUri = request.artUri
        kind = request.kind
        source = request.source
        senderName = request.senderName
        senderIp = request.senderIp
        lastKnownPosition = request.startPositionMs
        lastKnownDuration = 0
        setState(State.TRANSITIONING, notify = false)
        broadcast()

        val existing = controller
        if (existing != null) {
            existing.load(request)
            return
        }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerActivity.EXTRA_URL, request.url)
            putExtra(PlayerActivity.EXTRA_KIND, request.kind.name)
            putExtra(PlayerActivity.EXTRA_TITLE, request.title)
            putExtra(PlayerActivity.EXTRA_ARTIST, request.artist)
            putExtra(PlayerActivity.EXTRA_ALBUM, request.album)
            putExtra(PlayerActivity.EXTRA_ART, request.artUri)
            putExtra(PlayerActivity.EXTRA_POSITION, request.startPositionMs)
            putExtra(PlayerActivity.EXTRA_SOURCE, request.source)
            putExtra(PlayerActivity.EXTRA_SENDER, request.senderName)
        }
        context.startActivity(intent)
    }

    fun reset() {
        uri = ""
        metadata = ""
        title = ""
        artist = ""
        album = ""
        artUri = ""
        source = ""
        senderName = ""
        senderIp = ""
        lastKnownPosition = 0
        lastKnownDuration = 0
        setState(State.NO_MEDIA)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase(Locale.US))
        .put("upnpState", state.upnp)
        .put("uri", uri)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("artUri", artUri)
        .put("kind", kind.name.lowercase(Locale.US))
        .put("source", source)
        .put("senderName", senderName)
        .put("senderIp", senderIp)
        .put("positionMs", positionMs())
        .put("durationMs", durationMs())
        .put("volume", volume)
        .put("muted", muted)

    // ---- UPnP time formatting: H:MM:SS(.mmm) ----------------------------------

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val total = ms / 1000
        return String.format(
            Locale.US, "%d:%02d:%02d",
            total / 3600, (total % 3600) / 60, total % 60,
        )
    }

    fun parseDuration(value: String): Long {
        val v = value.trim()
        if (v.isEmpty() || v == "NOT_IMPLEMENTED") return 0
        // Accept both "0:01:23" and raw seconds.
        if (!v.contains(':')) return (v.toDoubleOrNull() ?: 0.0).times(1000).toLong()
        val parts = v.split(':')
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].toDouble()
            ((h * 3600 + m * 60) * 1000) + (s * 1000).toLong()
        } catch (_: Exception) {
            0
        }
    }
}
