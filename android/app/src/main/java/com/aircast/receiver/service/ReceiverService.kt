package com.aircast.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.aircast.receiver.MainActivity
import com.aircast.receiver.R
import com.aircast.receiver.airplay.AirPlayHandler
import com.aircast.receiver.airplay.LocalHostname
import com.aircast.receiver.airplay.NsdAdvertiser
import com.aircast.receiver.core.AccessGate
import com.aircast.receiver.core.Events
import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.HttpServer
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.OverlayActivity
import android.provider.Settings
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.core.Sessions
import com.aircast.receiver.dlna.DlnaHandler
import com.aircast.receiver.dlna.Gena
import com.aircast.receiver.dlna.Ssdp
import com.aircast.receiver.mirror.MirrorHandler
import com.aircast.receiver.mirror.MirrorSignaling
import com.aircast.receiver.mirror.TlsFactory
import com.aircast.receiver.player.Playback
import com.aircast.receiver.player.Subtitles
import com.aircast.receiver.net.SmbBrowser
import com.aircast.receiver.net.SmbStream
import com.aircast.receiver.net.PrefsHolder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds every listener the receiver needs, for as long as the user wants to be
 * discoverable.
 *
 * It is a foreground service on purpose: without one, Android freezes the sockets a
 * few minutes after the screen turns off and the box quietly disappears from every
 * sender on the network — the single most common complaint about receivers that try
 * to live in an Activity.
 */
class ReceiverService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var dlna: DlnaHandler
    private lateinit var airplay: AirPlayHandler
    private lateinit var mirror: MirrorHandler

    /** Wire Prefs into the standalone SMB helpers once at service start. */
    private fun initNetHelpers() {
        try {
            PrefsHolder.prefs = prefs
        } catch (e: Exception) {
            Logger.w("service", "net helpers init failed: ${e.message}")
        }
    }

    private var httpServer: HttpServer? = null
    private var httpsServer: HttpServer? = null
    private var airplayServer: HttpServer? = null
    private var ssdp: Ssdp? = null
    private var nsd: NsdAdvertiser? = null
    private var hostname: LocalHostname? = null
    private var cast: com.aircast.receiver.cast.CastReceiver? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var tickThread: Thread? = null
    private val ticking = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastIp: String = ""

    private val playbackListener: (Playback.State) -> Unit = {
        dlna.pushTransportState()
        updateNotification()
    }

    /**
     * `smartVideoQuality`: when a mirror sender is dropping frames, quietly lower the
     * mirroring resolution cap so the link has headroom again — then keep it there.
     */
    private val diagnosticsListener = Events.Listener { name, _ ->
        if (name != "decoderStall" || !prefs.smartVideoQuality) return@Listener
        try {
            val current = prefs.mirrorQuality
            if (current > 0) {
                val next = (current - 360).coerceAtLeast(720)
                if (next != current) {
                    prefs.mirrorQuality = next
                    Logger.i("service", "smart quality lowered mirror cap to ${next}p after frame stalls")
                }
            }
        } catch (_: Exception) {
            /* preferences are best-effort */
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        dlna = DlnaHandler(this)
        airplay = AirPlayHandler(this)
        mirror = MirrorHandler(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopEverything()
                startEverything()
            }
            else -> {
                startForegroundSafely()
                startEverything()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- lifecycle ----------------------------------------------------------

    @Synchronized
    private fun startEverything() {
        if (isRunning) return
        instance = this
        AccessGate.init(this)
        initNetHelpers()
        Sessions.prefs = prefs
        prefs.bootId = prefs.bootId + 1
        lastIp = Net.primaryIp()
        Net.registerWatcher(this)

        startHttp()
        startHttps()
        startAirPlay()

        if (prefs.dlnaEnabled) {
            ssdp = Ssdp(this) { prefs.httpPort }.also { it.start() }
        }
        // The Cast control channel comes up before the mDNS record that points at it, so
        // a sender that reacts to the announcement instantly still finds an open port.
        cast = com.aircast.receiver.cast.CastReceiver(this).also { it.start() }
        if (prefs.airplayEnabled) {
            nsd = NsdAdvertiser(this).also { it.start(prefs.airplayPort, prefs.httpPort) }
        }
        // Stable aircast.local name so a bookmark survives the IP changing.
        hostname = LocalHostname(this).also { it.start(lastIp) }

        acquireWakeLock()
        Playback.addStateListener(playbackListener)
        registerNetworkCallback()
        Events.addListener(diagnosticsListener)
        startTicking()
        syncOverlayActivity()

        isRunning = true
        Logger.i("service", "receiver online as \"${prefs.deviceName}\" at $lastIp")
        updateNotification()
        broadcastStatus()
    }

    @Synchronized
    private fun stopEverything() {
        if (!isRunning && httpServer == null) return
        isRunning = false
        ticking.set(false)
        tickThread?.interrupt()
        tickThread = null

        Playback.removeStateListener(playbackListener)
        unregisterNetworkCallback()
        Events.removeListener(diagnosticsListener)
        Net.unregisterWatcher(this)

        ssdp?.stop(); ssdp = null
        nsd?.stop(); nsd = null
        hostname?.stop(); hostname = null
        cast?.stop(); cast = null
        httpServer?.stop(); httpServer = null
        httpsServer?.stop(); httpsServer = null
        airplayServer?.stop(); airplayServer = null

        MirrorSignaling.closeAll()
        Sessions.clear()
        Gena.clear()
        releaseWakeLock()
        stopOverlayActivity()
        instance = null
        Logger.i("service", "receiver offline")
        broadcastStatus()
    }

    // ---- background overlay (screensaver canvas) ----------------------------

    /**
     * Mirrors AirScreen's background screensaver: when the receiver is on and the
     * `canvas` mode is active, an animated overlay window sits above the home screen
     * so the device stays visibly alive even with the app closed.
     */
    @Synchronized
    fun syncOverlayActivity() {
        val wantCanvas = prefs.backgroundMode == "canvas"
        if (!wantCanvas) {
            stopOverlayActivity()
            return
        }
        val hasPermission = try {
            Settings.canDrawOverlays(this)
        } catch (_: Exception) {
            false
        }
        if (!hasPermission) {
            Logger.w("service", "overlay canvas needs SYSTEM_ALERT_WINDOW — permission not granted")
            return
        }
        try {
            val intent = Intent(this, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e("service", "overlay canvas failed: ${e.message}")
        }
    }

    private fun stopOverlayActivity() {
        try {
            val intent = Intent(this, OverlayActivity::class.java).apply {
                action = OverlayActivity.ACTION_HIDE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            /* overlay may not be running — that's fine */
        }
    }

    /** Applies changed settings without dropping active playback where possible. */
    @Synchronized
    fun reconfigure() {
        Logger.i("service", "applying new settings")
        stopEverything()
        startEverything()
    }

    // ---- servers ------------------------------------------------------------

    private fun startHttp() {
        try {
            httpServer = HttpServer(prefs.httpPort, "http", secure = false) { route(it, secure = false) }
                .also { it.start() }
        } catch (e: Exception) {
            Logger.e("service", "HTTP port ${prefs.httpPort} unavailable: ${e.message}")
        }
    }

    private fun startHttps() {
        if (!prefs.mirrorEnabled) return
        val factory = TlsFactory.serverSocketFactory(this)
        if (factory == null) {
            Logger.w("service", "TLS unavailable — browser mirroring will not be offered")
            return
        }
        try {
            httpsServer = HttpServer(
                prefs.httpsPort, "https", secure = true, serverSocketFactory = factory,
            ) { route(it, secure = true) }.also { it.start() }
        } catch (e: Exception) {
            Logger.e("service", "HTTPS port ${prefs.httpsPort} unavailable: ${e.message}")
        }
    }

    private fun startAirPlay() {
        if (!prefs.airplayEnabled) return
        try {
            airplayServer = HttpServer(prefs.airplayPort, "airplay", secure = false) { req ->
                airplay.handle(req) ?: route(req, secure = false)
            }.also { it.start() }
        } catch (e: Exception) {
            Logger.e("service", "AirPlay port ${prefs.airplayPort} unavailable: ${e.message}")
        }
    }

    /** Shared routing table for the plain and TLS listeners. */
    private fun route(req: HttpRequest, secure: Boolean): HttpResponse? {
        mirror.handle(req)?.let { return it }
        if (prefs.smbEnabled) {
            SmbStream.handle(req)?.let { return it }
            if (req.path.startsWith("/browse")) {
                return try {
                    val index = req.query["server"]?.toIntOrNull() ?: 0
                    val path = req.query["path"] ?: ""
                    val filter = req.query["filter"] ?: ""
                    HttpResponse.json(SmbBrowser.browse(index, path, filter))
                } catch (e: Exception) {
                    Logger.w("smb", "browse failed: ${e.message}")
                    HttpResponse.json(JSONObject()
                        .put("error", e.message ?: "browse failed").toString(), 503)
                }
            }
        }
        Subtitles.handle(req)?.let { return it }
        if (prefs.dlnaEnabled && !secure) dlna.handle(req)?.let { return it }
        if (prefs.airplayEnabled && !secure) airplay.handle(req)?.let { return it }
        return HttpResponse.notFound()
    }

    // ---- housekeeping -------------------------------------------------------

    private fun startTicking() {
        ticking.set(true)
        tickThread = Thread({
            while (ticking.get()) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!ticking.get()) return@Thread
                try {
                    Sessions.sweep()
                    MirrorSignaling.sweep()
                    Gena.sweep()
                    AccessGate.sweepPending()
                    checkAddressChange()
                    broadcastStatus()
                } catch (e: Exception) {
                    Logger.w("service", "tick failed: ${e.message}")
                }
            }
        }, "receiver-tick").apply { isDaemon = true; start() }
    }

    /**
     * A DHCP renewal that changes the IP silently invalidates the advertised LOCATION
     * URL and the TLS certificate's SANs, so both have to be rebuilt when it happens.
     */
    private fun checkAddressChange() {
        val ip = Net.primaryIp()
        if (ip == lastIp) return
        Logger.i("service", "address changed $lastIp -> $ip; re-advertising")
        lastIp = ip
        TlsFactory.invalidate()
        httpsServer?.stop(); httpsServer = null
        startHttps()
        ssdp?.refresh()
        nsd?.let {
            it.stop()
            it.start(prefs.airplayPort, prefs.httpPort)
        }
        hostname?.start(ip)
        broadcastStatus()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = postAddressCheck()
                override fun onLost(network: Network) = postAddressCheck()
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Logger.w("service", "network callback unavailable: ${e.message}")
        }
    }

    private fun postAddressCheck() {
        Thread {
            // The new address is not readable the instant the callback fires.
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return@Thread }
            try { checkAddressChange() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun acquireWakeLock() {
        if (!prefs.keepScreenOn) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aircast:receiver").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Logger.w("service", "wake lock unavailable: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ---- notification -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when {
            Playback.state == Playback.State.PLAYING && Playback.title.isNotBlank() ->
                getString(R.string.notification_playing, Playback.title)
            Sessions.count() > 0 -> getString(R.string.notification_connected, Sessions.count())
            else -> getString(R.string.notification_ready, Net.primaryIp())
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(prefs.deviceName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.action_stop), stop,
                ).build(),
            )
        return builder.build()
    }

    private fun startForegroundSafely() {
        try {
            when {
                // `specialUse` is the only accurate type here — the service holds
                // sockets and discovery, it does not itself play anything — and it is
                // the one that survives being started from BOOT_COMPLETED on API 34+.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )

                else -> startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Logger.e("service", "startForeground failed: ${e.message}")
        }
    }

    private fun updateNotification() {
        if (!isRunning) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun broadcastStatus() = Events.emit("statusChanged", status(this))

    companion object {
        const val ACTION_START = "com.aircast.receiver.START"
        const val ACTION_STOP = "com.aircast.receiver.STOP"
        const val ACTION_RESTART = "com.aircast.receiver.RESTART"

        private const val CHANNEL_ID = "aircast_receiver"
        private const val NOTIFICATION_ID = 4211
        private const val TICK_MS = 12_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: ReceiverService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ReceiverService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            if (!isRunning) { start(context); return }
            context.startService(
                Intent(context, ReceiverService::class.java).setAction(ACTION_RESTART),
            )
        }

        /** The full snapshot the UI renders from. */
        fun status(context: Context): JSONObject {
            val prefs = Prefs.get(context)
            val ip = Net.primaryIp()
            val ips = JSONArray().also { arr -> Net.localIpv4Addresses().forEach { arr.put(it) } }
            return JSONObject()
                .put("running", isRunning)
                .put("deviceName", prefs.deviceName)
                .put("ip", ip)
                .put("ips", ips)
                .put("ssid", Net.ssid(context) ?: JSONObject.NULL)
                .put("transport", Net.transportName(context))
                .put("connected", Net.isConnected(context))
                .put("httpPort", prefs.httpPort)
                .put("httpsPort", prefs.httpsPort)
                .put("airplayPort", prefs.airplayPort)
                .put("landingUrl", "http://$ip:${prefs.httpPort}/")
                .put("mirrorUrl", "https://$ip:${prefs.httpsPort}/cast")
                .put("tlsReady", instance?.httpsServer != null)
                .put(
                    "protocols",
                    JSONObject()
                        .put("dlna", prefs.dlnaEnabled)
                        .put("airplay", prefs.airplayEnabled)
                        .put("mirror", prefs.mirrorEnabled),
                )
                .put("sessions", Sessions.toJsonArray())
                .put("mirrorPeers", MirrorSignaling.peersJson())
                .put("activeMirrors", MirrorSignaling.activeCount())
                .put("playback", Playback.toJson())
                .put("recording", com.aircast.receiver.record.RecorderService.isRecording)
        }
    }
}
