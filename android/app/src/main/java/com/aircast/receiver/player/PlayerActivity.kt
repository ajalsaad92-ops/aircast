package com.aircast.receiver.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.content.pm.ActivityInfo
import androidx.media3.common.VideoSize
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aircast.receiver.R
import com.aircast.receiver.core.Events
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The renderer surface. Every protocol funnels here, so DLNA video, an AirPlay photo and
 * a music track all reuse the same window, transport state and volume.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : Activity(), Playback.Controller {

    private lateinit var playerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var overlay: View
    private lateinit var audioArt: ImageView

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var photoThread: Thread? = null

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    /**
     * `forcedRotation`: `horizontal` locks landscape and `vertical` locks portrait, so a
     * tablet/TV box can be forced into the orientation the content was designed for —
     * the same knob AirScreen exposes under display settings.
     */
    private fun keepPlayingEnabled(): Boolean =
        runCatching { Prefs.get(this).keepPlaying }.getOrDefault(false)

    /**
     * Caps the video surface at the chosen resolution so decoding happens at the capped
     * size on slower boxes. `native` clears any previous cap.
     */
    private fun applyResolutionCap() {
        val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView
        val holder = surfaceView?.holder ?: return
        val mode = runCatching { Prefs.get(this).screenResolution }.getOrDefault("native")
        val cap = when (mode) {
            "720p" -> 1280 to 720
            "1080p" -> 1920 to 1080
            "4k" -> 3840 to 2160
            else -> 0 to 0
        }
        if (cap.first > 0) {
            val (w, h) = cap
            val vw = surfaceView.width.takeIf { it > 0 } ?: Int.MAX_VALUE
            val vh = surfaceView.height.takeIf { it > 0 } ?: Int.MAX_VALUE
            holder.setFixedSize(minOf(w, vw).coerceAtLeast(1), minOf(h, vh).coerceAtLeast(1))
        } else {
            runCatching { holder.setSizeFromLayout() }
        }
    }

    private fun applyForcedRotation() {
        val mode = runCatching { Prefs.get(this).forcedRotation }.getOrDefault("auto")
        requestedOrientation = when (mode) {
            "horizontal" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "vertical" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            player?.let { Playback.cachePosition(it.currentPosition, maxOf(it.duration, 0L)) }
            main.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        applyForcedRotation()

        playerView = findViewById(R.id.playerView)
        photoView = findViewById(R.id.photoView)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        overlay = findViewById(R.id.overlay)
        audioArt = findViewById(R.id.audioArt)

        goImmersive()
        Playback.controller = this
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) { setIntent(intent); handleIntent(intent) }
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        load(
            Playback.Request(
                url = url,
                kind = runCatching {
                    Playback.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "VIDEO")
                }.getOrDefault(Playback.Kind.VIDEO),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
                artUri = intent.getStringExtra(EXTRA_ART).orEmpty(),
                startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                source = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
                senderName = intent.getStringExtra(EXTRA_SENDER).orEmpty(),
            ),
        )
    }

    // ---- Playback.Controller ------------------------------------------------

    override fun load(request: Playback.Request) {
        main.post {
            showOverlay(request)
            when (request.kind) {
                Playback.Kind.IMAGE -> showPhoto(request)
                else -> showMedia(request)
            }
        }
    }

    override fun play() = main.post { player?.play() }.let { }

    override fun pause() = main.post { player?.pause() }.let { }

    override fun stopPlayback() {
        main.post {
            player?.stop()
            Playback.setState(Playback.State.STOPPED)
            finish()
        }
    }

    override fun seekTo(positionMs: Long) = main.post { player?.seekTo(positionMs) }.let { }

    override fun setVolumePercent(percent: Int) = main.post {
        player?.volume = (percent.coerceIn(0, 100)) / 100f
    }.let { }

    override fun setMuted(muted: Boolean) = main.post {
        player?.volume = if (muted) 0f else Playback.volume / 100f
    }.let { }

    override fun currentPositionMs(): Long = player?.currentPosition ?: 0L

    override fun durationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    // ---- rendering ----------------------------------------------------------

    private fun showMedia(request: Playback.Request) {
        photoThread?.interrupt()
        photoView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        audioArt.visibility = if (request.kind == Playback.Kind.AUDIO) View.VISIBLE else View.GONE

        val exo = player ?: ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    // Falls back to a software decoder when the hardware one rejects the
                    // stream, instead of showing a black screen.
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .build()
            .also { if (keepPlayingEnabled()) it.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL) }
            .also { created ->
                created.addListener(playerListener)
                playerView.player = created
                player = created
                main.post(progressTick)
            }

        val item = MediaItem.Builder()
            .setUri(request.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title.ifBlank { null })
                    .setArtist(request.artist.ifBlank { null })
                    .setAlbumTitle(request.album.ifBlank { null })
                    .build(),
            )
            .build()

        exo.setMediaItem(item)
        exo.volume = if (Playback.muted) 0f else Playback.volume / 100f

        // `screenResolution`: shrink the decoder output surface so the renderer never
        // works above the chosen cap — the same role as AirScreen's resolution picker.
        applyResolutionCap()

        exo.prepare()
        if (request.startPositionMs > 0) exo.seekTo(request.startPositionMs)
        exo.playWhenReady = true
    }

    private fun showPhoto(request: Playback.Request) {
        player?.stop()
        playerView.visibility = View.GONE
        audioArt.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        Playback.setState(Playback.State.PLAYING)

        photoThread?.interrupt()
        photoThread = Thread {
            val bitmap = loadBitmap(request.url)
            if (Thread.currentThread().isInterrupted) return@Thread
            main.post {
                if (bitmap != null) photoView.setImageBitmap(bitmap)
                else Logger.w("player", "could not decode ${request.url}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        when {
            url.startsWith("file:") -> BitmapFactory.decodeFile(File(URL(url).path).absolutePath)
            url.startsWith("/") -> BitmapFactory.decodeFile(url)
            else -> {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        }
    } catch (e: Exception) {
        Logger.w("player", "image load failed: ${e.message}")
        null
    }

    private fun showOverlay(request: Playback.Request) {
        titleView.text = request.title.ifBlank { getString(R.string.player_untitled) }
        val from = request.senderName.ifBlank { request.source.uppercase() }
        subtitleView.text = listOf(request.artist, from).filter { it.isNotBlank() }.joinToString(" · ")
        overlay.visibility = View.VISIBLE
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, 4000)
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            if (size == VideoSize.UNKNOWN) return
            val fps = player?.let { (it.videoFormat?.frameRate ?: 0f) } ?: 0f
            Events.emit(
                "videoQuality",
                org.json.JSONObject()
                    .put("width", size.width)
                    .put("height", size.height)
                    .put("fps", fps),
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> Playback.setState(Playback.State.TRANSITIONING)
                Player.STATE_READY -> Playback.setState(
                    if (player?.playWhenReady == true) Playback.State.PLAYING else Playback.State.PAUSED,
                )
                Player.STATE_ENDED -> {
                    Playback.setState(Playback.State.STOPPED)
                    main.postDelayed({ if (!isFinishing) finish() }, 1200)
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player?.playbackState == Player.STATE_READY) {
                Playback.setState(if (isPlaying) Playback.State.PLAYING else Playback.State.PAUSED)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Logger.e("player", "playback error: ${error.errorCodeName} ${error.message}")
            Playback.setState(Playback.State.STOPPED)
            titleView.text = getString(R.string.player_error)
            subtitleView.text = error.errorCodeName
            overlay.visibility = View.VISIBLE
        }
    }

    // ---- window / input -----------------------------------------------------

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** D-pad handling so the box is usable with a TV remote, not just a touchscreen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exo = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (exo != null) { if (exo.isPlaying) exo.pause() else exo.play(); return true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                exo?.seekTo(exo.currentPosition + 10_000); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                exo?.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopPlayback(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        photoThread?.interrupt()
        if (Playback.controller === this) Playback.controller = null
        player?.let {
            Playback.cachePosition(it.currentPosition, maxOf(it.duration, 0L))
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        if (Playback.state != Playback.State.NO_MEDIA) Playback.setState(Playback.State.STOPPED)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ART = "art"
        const val EXTRA_POSITION = "position"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENDER = "sender"
    }
}
