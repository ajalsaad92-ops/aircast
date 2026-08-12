package com.aircast.receiver.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import com.aircast.receiver.MainActivity
import com.aircast.receiver.R
import com.aircast.receiver.core.Events
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Prefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records whatever is on the receiver's screen — the AirScreen feature people use to
 * capture a mirrored session.
 *
 * The ordering here is load-bearing on Android 14+: the service must already be in the
 * foreground *with* `mediaProjection` type before `getMediaProjection()` is called, or
 * the platform throws `SecurityException`.
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startForegroundSafely()
                if (data == null || !begin(resultCode, data)) {
                    emit(false, null, "could not start capture")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- recording ----------------------------------------------------------

    private fun begin(resultCode: Int, data: Intent): Boolean {
        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, data) ?: return false
            projection = mp
            mp.registerCallback(projectionCallback, null)

            val metrics = displayMetrics()
            val width = evenize(metrics.widthPixels)
            val height = evenize(metrics.heightPixels)

            val file = newOutputFile()
            outputFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            val withAudio = Prefs.get(this).recordAudio
            if (withAudio) {
                try {
                    rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                } catch (e: Exception) {
                    Logger.w("record", "microphone unavailable: ${e.message}")
                }
            }
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(file.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(bitrateFor(height))
            if (withAudio) {
                try {
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(128_000)
                    rec.setAudioSamplingRate(44_100)
                } catch (_: Exception) {
                }
            }
            rec.prepare()

            virtualDisplay = mp.createVirtualDisplay(
                "aircast-record",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null,
            )
            rec.start()
            recorder = rec
            startedAt = System.currentTimeMillis()
            Logger.i("record", "recording ${width}x$height -> ${file.name}")
            emit(true, file.absolutePath, null)
            true
        } catch (e: Exception) {
            Logger.e("record", "start failed: ${e.message}")
            cleanup()
            false
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return cleanup()
        try {
            rec.stop()
        } catch (e: Exception) {
            // Stopping within a second of starting produces no valid file.
            Logger.w("record", "stop failed (clip too short?): ${e.message}")
            outputFile?.delete()
            outputFile = null
        }
        try { rec.reset(); rec.release() } catch (_: Exception) {}
        recorder = null
        cleanup()

        val file = outputFile
        if (file != null && file.exists() && file.length() > 0) {
            val uri = publish(file)
            Logger.i("record", "saved ${file.name} (${file.length() / 1024} KB)")
            emit(false, uri?.toString() ?: file.absolutePath, null)
        } else {
            emit(false, null, "no data recorded")
        }
        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        isRecording = false
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.i("record", "capture revoked by the system")
            stopRecording()
            stopSelf()
        }
    }

    /** Makes the clip visible to the gallery instead of hiding in app-private storage. */
    private fun publish(file: File): Uri? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AirCast")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                file.delete()
            }
            uri
        } else {
            Uri.fromFile(file)
        }
    } catch (e: Exception) {
        Logger.w("record", "could not add to gallery: ${e.message}")
        null
    }

    private fun emit(recording: Boolean, path: String?, error: String?) {
        isRecording = recording
        Events.emit(
            "recordingChanged",
            JSONObject()
                .put("recording", recording)
                .put("file", path ?: JSONObject.NULL)
                .put("error", error ?: JSONObject.NULL)
                .put("startedAt", startedAt),
        )
    }

    private fun newOutputFile(): File {
        val dir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
            "recordings",
        ).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "AirCast-$stamp.mp4")
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /** H.264 encoders reject odd dimensions on many chipsets. */
    private fun evenize(value: Int) = if (value % 2 == 0) value else value - 1

    private fun bitrateFor(height: Int) = when {
        height >= 2000 -> 20_000_000
        height >= 1400 -> 12_000_000
        height >= 1000 -> 8_000_000
        else -> 4_000_000
    }

    // ---- notification -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.recording_channel_desc) },
        )
    }

    private fun startForegroundSafely() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(getString(R.string.recording_text))
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Logger.e("record", "startForeground failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_START = "com.aircast.receiver.RECORD_START"
        const val ACTION_STOP = "com.aircast.receiver.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "aircast_recording"
        private const val NOTIFICATION_ID = 4212

        @Volatile
        var isRecording: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecorderService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
