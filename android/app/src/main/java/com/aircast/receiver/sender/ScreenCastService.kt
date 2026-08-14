package com.aircast.receiver.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.aircast.receiver.R
import com.aircast.receiver.core.Logger

/**
 * Foreground service that owns the MediaProjection while [ScreenSender] streams this
 * device's screen to another AirCast receiver. Declared with the mediaProjection FGS
 * type; casting stops when the projection is revoked or [stop] is called.
 */
class ScreenCastService : Service() {

    private var sender: ScreenSender? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val host = intent?.getStringExtra(EXTRA_HOST)
        if (data == null || host.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra(EXTRA_PORT, 8321)
        val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: "AirCast phone"

        startForegroundSafe()
        instance = this
        sender = ScreenSender(applicationContext).also { it.start(data, host, port, pin, name) }
        Logger.i("sender", "casting to $host:$port")
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val channelId = "aircast_cast"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "Casting", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("AirCast")
            .setContentText("Casting this screen")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun shutdown() {
        try { sender?.stop() } catch (_: Exception) {}
        sender = null
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4210
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val EXTRA_NAME = "name"
        const val ACTION_STOP = "com.aircast.receiver.CAST_STOP"

        @Volatile
        var instance: ScreenCastService? = null
            private set

        val isCasting: Boolean get() = instance != null

        fun start(ctx: Context, data: Intent, host: String, port: Int, pin: String, name: String) {
            val i = Intent(ctx, ScreenCastService::class.java)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_PIN, pin)
                .putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenCastService::class.java).setAction(ACTION_STOP))
        }
    }
}
