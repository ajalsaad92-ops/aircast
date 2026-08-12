package com.aircast.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResult
import com.aircast.receiver.core.Events
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.mirror.MirrorSignaling
import com.aircast.receiver.mirror.TlsFactory
import com.aircast.receiver.record.RecorderService
import com.aircast.receiver.service.ReceiverService
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONObject

/**
 * The bridge between the native receiver stack and the React UI.
 *
 * Everything the interface shows — status, sessions, logs, playback — is read through
 * `getStatus()` plus a stream of events, so the WebView never owns protocol state.
 *
 * The one place the WebView does real work is mirroring: it is the WebRTC peer, so the
 * SDP answer and ICE candidates flow back out through `mirrorAnswer` / `mirrorCandidate`.
 */
@CapacitorPlugin(
    name = "AirCast",
    permissions = [
        Permission(alias = AirCastPlugin.PERM_NOTIFICATIONS, strings = [Manifest.permission.POST_NOTIFICATIONS]),
        Permission(alias = AirCastPlugin.PERM_MICROPHONE, strings = [Manifest.permission.RECORD_AUDIO]),
    ],
)
class AirCastPlugin : Plugin() {

    private val listener = Events.Listener { name, data -> forward(name, data) }

    override fun load() {
        super.load()
        Events.addListener(listener)
    }

    override fun handleOnDestroy() {
        Events.removeListener(listener)
        super.handleOnDestroy()
    }

    private fun forward(name: String, data: JSONObject) {
        try {
            notifyListeners(name, JSObject.fromJSONObject(data))
        } catch (e: Exception) {
            Logger.w("plugin", "could not forward $name: ${e.message}")
        }
    }

    private fun appContext(): Context = context.applicationContext

    // ---- lifecycle ----------------------------------------------------------

    @PluginMethod
    fun start(call: PluginCall) {
        ReceiverService.start(appContext())
        call.resolve(statusObject())
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        ReceiverService.stop(appContext())
        call.resolve(statusObject())
    }

    @PluginMethod
    fun restart(call: PluginCall) {
        ReceiverService.restart(appContext())
        call.resolve(statusObject())
    }

    @PluginMethod
    fun getStatus(call: PluginCall) = call.resolve(statusObject())

    private fun statusObject(): JSObject =
        JSObject.fromJSONObject(ReceiverService.status(appContext()))
            .put("recording", RecorderService.isRecording)

    // ---- settings -----------------------------------------------------------

    @PluginMethod
    fun getSettings(call: PluginCall) {
        call.resolve(JSObject.fromJSONObject(Prefs.get(appContext()).toJson()))
    }

    /**
     * Applies settings and, when anything the network layer depends on changed,
     * restarts the listeners so the new name/ports are actually advertised.
     */
    @PluginMethod
    fun setSettings(call: PluginCall) {
        val prefs = Prefs.get(appContext())
        val before = prefs.toJson().toString()
        prefs.applyJson(JSONObject(call.data.toString()))
        val after = prefs.toJson().toString()

        if (before != after && ReceiverService.isRunning) {
            ReceiverService.instance?.reconfigure() ?: ReceiverService.restart(appContext())
        }
        call.resolve(JSObject.fromJSONObject(prefs.toJson()))
    }

    @PluginMethod
    fun getNetworkInfo(call: PluginCall) {
        val ips = JSArray()
        Net.localIpv4Addresses().forEach { ips.put(it) }
        call.resolve(
            JSObject()
                .put("ip", Net.primaryIp())
                .put("ips", ips)
                .put("ssid", Net.ssid(appContext()))
                .put("transport", Net.transportName(appContext()))
                .put("connected", Net.isConnected(appContext())),
        )
    }

    @PluginMethod
    fun getTlsFingerprint(call: PluginCall) {
        call.resolve(JSObject().put("fingerprint", TlsFactory.fingerprint(appContext())))
    }

    // ---- diagnostics --------------------------------------------------------

    @PluginMethod
    fun getLogs(call: PluginCall) {
        val arr = JSArray()
        for (line in Logger.snapshot()) {
            val level = line.substringBefore('|')
            arr.put(JSObject().put("level", level).put("line", line.substringAfter('|')))
        }
        call.resolve(JSObject().put("lines", arr))
    }

    @PluginMethod
    fun clearLogs(call: PluginCall) {
        Logger.clear()
        call.resolve()
    }

    // ---- mirroring ----------------------------------------------------------

    @PluginMethod
    fun mirrorAnswer(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        val sdp = call.getString("sdp") ?: return call.reject("sdp is required")
        val ok = MirrorSignaling.setAnswer(id, sdp)
        call.resolve(JSObject().put("ok", ok))
    }

    @PluginMethod
    fun mirrorCandidate(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        val candidate = call.getObject("candidate") ?: return call.reject("candidate is required")
        MirrorSignaling.addReceiverCandidate(id, candidate.toString())
        call.resolve()
    }

    @PluginMethod
    fun mirrorGetCandidates(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        val since = call.getInt("since") ?: 0
        val peer = MirrorSignaling.peer(id)
            ?: return call.resolve(JSObject().put("candidates", JSArray()))
        // Round-tripping through text rather than JSArray.from(): that helper is
        // documented for arrays and collections, not for a JSONArray.
        val arr = MirrorSignaling.candidatesSince(peer.senderCandidates, since)
        call.resolve(JSObject().put("candidates", JSArray(arr.toString())))
    }

    @PluginMethod
    fun mirrorEnd(call: PluginCall) {
        val id = call.getString("id")
        if (id == null) MirrorSignaling.closeAll() else MirrorSignaling.close(id, "stopped from receiver")
        call.resolve()
    }

    // ---- recording ----------------------------------------------------------

    @PluginMethod
    fun startRecording(call: PluginCall) {
        if (RecorderService.isRecording) return call.resolve(JSObject().put("recording", true))
        try {
            val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            startActivityForResult(call, manager.createScreenCaptureIntent(), "captureResult")
        } catch (e: Exception) {
            call.reject("Screen capture unavailable: ${e.message}")
        }
    }

    @ActivityCallback
    private fun captureResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            call.resolve(JSObject().put("recording", false).put("cancelled", true))
            return
        }
        RecorderService.start(appContext(), result.resultCode, data)
        call.resolve(JSObject().put("recording", true))
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        RecorderService.stop(appContext())
        call.resolve(JSObject().put("recording", false))
    }

    @PluginMethod
    fun getRecordingState(call: PluginCall) {
        call.resolve(JSObject().put("recording", RecorderService.isRecording))
    }

    // ---- permissions & misc -------------------------------------------------

    @PluginMethod
    fun ensureNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return call.resolve(JSObject().put("granted", true))
        }
        if (getPermissionState(PERM_NOTIFICATIONS) == com.getcapacitor.PermissionState.GRANTED) {
            return call.resolve(JSObject().put("granted", true))
        }
        requestPermissionForAlias(PERM_NOTIFICATIONS, call, "notificationResult")
    }

    @PermissionCallback
    private fun notificationResult(call: PluginCall) {
        call.resolve(
            JSObject().put(
                "granted",
                getPermissionState(PERM_NOTIFICATIONS) == com.getcapacitor.PermissionState.GRANTED,
            ),
        )
    }

    /**
     * Opens a page full-screen inside AirCast. Used for headset casting, where the
     * headset streams to a web page instead of to a discoverable receiver.
     */
    @PluginMethod
    fun openCastPage(call: PluginCall) {
        val url = call.getString("url") ?: com.aircast.receiver.cast.CastWebActivity.DEFAULT_URL
        try {
            activity.startActivity(
                Intent(activity, com.aircast.receiver.cast.CastWebActivity::class.java)
                    .putExtra(com.aircast.receiver.cast.CastWebActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            call.resolve(JSObject().put("opened", true))
        } catch (e: Exception) {
            call.reject("Cannot open the cast page: ${e.message}")
        }
    }

    @PluginMethod
    fun openExternal(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("url is required")
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            call.resolve()
        } catch (e: Exception) {
            call.reject("Nothing can open $url")
        }
    }

    companion object {
        const val PERM_NOTIFICATIONS = "notifications"
        const val PERM_MICROPHONE = "microphone"
    }
}
