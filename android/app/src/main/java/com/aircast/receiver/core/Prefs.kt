package com.aircast.receiver.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Persisted settings. Single source of truth shared by the service, the plugin and the UI. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("aircast", Context.MODE_PRIVATE)

    var deviceName: String
        get() = sp.getString(K_NAME, null) ?: Net.defaultDeviceName()
        set(v) = sp.edit().putString(K_NAME, v.trim().ifEmpty { Net.defaultDeviceName() }).apply()

    var dlnaEnabled: Boolean
        get() = sp.getBoolean(K_DLNA, true)
        set(v) = sp.edit().putBoolean(K_DLNA, v).apply()

    var airplayEnabled: Boolean
        get() = sp.getBoolean(K_AIRPLAY, true)
        set(v) = sp.edit().putBoolean(K_AIRPLAY, v).apply()

    var mirrorEnabled: Boolean
        get() = sp.getBoolean(K_MIRROR, true)
        set(v) = sp.edit().putBoolean(K_MIRROR, v).apply()

    /** Ask on-screen before a new sender is allowed to take over the display. */
    var requireApproval: Boolean
        get() = sp.getBoolean(K_APPROVAL, false)
        set(v) = sp.edit().putBoolean(K_APPROVAL, v).apply()

    var pinCode: String
        get() = sp.getString(K_PIN, "") ?: ""
        set(v) = sp.edit().putString(K_PIN, v.filter { it.isDigit() }.take(6)).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(K_AUTOSTART, true)
        set(v) = sp.edit().putBoolean(K_AUTOSTART, v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_AWAKE, true)
        set(v) = sp.edit().putBoolean(K_AWAKE, v).apply()

    var recordAudio: Boolean
        get() = sp.getBoolean(K_REC_AUDIO, true)
        set(v) = sp.edit().putBoolean(K_REC_AUDIO, v).apply()

    var language: String
        get() = sp.getString(K_LANG, "ar") ?: "ar"
        set(v) = sp.edit().putString(K_LANG, v).apply()

    /** Preferred cap for the mirroring track: 720 / 1080 / 2160 (0 = source). */
    var mirrorQuality: Int
        get() = sp.getInt(K_QUALITY, 1080)
        set(v) = sp.edit().putInt(K_QUALITY, v).apply()

    var httpPort: Int
        get() = sp.getInt(K_HTTP_PORT, DEFAULT_HTTP_PORT)
        set(v) = sp.edit().putInt(K_HTTP_PORT, v).apply()

    var httpsPort: Int
        get() = sp.getInt(K_HTTPS_PORT, DEFAULT_HTTPS_PORT)
        set(v) = sp.edit().putInt(K_HTTPS_PORT, v).apply()

    var airplayPort: Int
        get() = sp.getInt(K_AIRPLAY_PORT, DEFAULT_AIRPLAY_PORT)
        set(v) = sp.edit().putInt(K_AIRPLAY_PORT, v).apply()

    var deviceIdHex: String?
        get() = sp.getString(K_DEVICE_ID, null)
        set(v) = sp.edit().putString(K_DEVICE_ID, v).apply()

    var uuid: String?
        get() = sp.getString(K_UUID, null)
        set(v) = sp.edit().putString(K_UUID, v).apply()

    /** Bumped every time the description changes so controllers refresh their cache. */
    var bootId: Int
        get() = sp.getInt(K_BOOT_ID, 1)
        set(v) = sp.edit().putInt(K_BOOT_ID, v).apply()

    fun toJson(): JSONObject = JSONObject()
        .put("deviceName", deviceName)
        .put("dlnaEnabled", dlnaEnabled)
        .put("airplayEnabled", airplayEnabled)
        .put("mirrorEnabled", mirrorEnabled)
        .put("requireApproval", requireApproval)
        .put("pinCode", pinCode)
        .put("autoStart", autoStart)
        .put("keepScreenOn", keepScreenOn)
        .put("recordAudio", recordAudio)
        .put("language", language)
        .put("mirrorQuality", mirrorQuality)
        .put("httpPort", httpPort)
        .put("httpsPort", httpsPort)
        .put("airplayPort", airplayPort)

    fun applyJson(o: JSONObject) {
        if (o.has("deviceName")) deviceName = o.optString("deviceName")
        if (o.has("dlnaEnabled")) dlnaEnabled = o.optBoolean("dlnaEnabled", true)
        if (o.has("airplayEnabled")) airplayEnabled = o.optBoolean("airplayEnabled", true)
        if (o.has("mirrorEnabled")) mirrorEnabled = o.optBoolean("mirrorEnabled", true)
        if (o.has("requireApproval")) requireApproval = o.optBoolean("requireApproval", false)
        if (o.has("pinCode")) pinCode = o.optString("pinCode")
        if (o.has("autoStart")) autoStart = o.optBoolean("autoStart", true)
        if (o.has("keepScreenOn")) keepScreenOn = o.optBoolean("keepScreenOn", true)
        if (o.has("recordAudio")) recordAudio = o.optBoolean("recordAudio", true)
        if (o.has("language")) language = o.optString("language", "ar")
        if (o.has("mirrorQuality")) mirrorQuality = o.optInt("mirrorQuality", 1080)
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 8321
        const val DEFAULT_HTTPS_PORT = 8322
        const val DEFAULT_AIRPLAY_PORT = 7000

        private const val K_NAME = "device_name"
        private const val K_DLNA = "dlna_enabled"
        private const val K_AIRPLAY = "airplay_enabled"
        private const val K_MIRROR = "mirror_enabled"
        private const val K_APPROVAL = "require_approval"
        private const val K_PIN = "pin_code"
        private const val K_AUTOSTART = "auto_start"
        private const val K_AWAKE = "keep_screen_on"
        private const val K_REC_AUDIO = "record_audio"
        private const val K_LANG = "language"
        private const val K_QUALITY = "mirror_quality"
        private const val K_HTTP_PORT = "http_port"
        private const val K_HTTPS_PORT = "https_port"
        private const val K_AIRPLAY_PORT = "airplay_port"
        private const val K_DEVICE_ID = "device_id_hex"
        private const val K_UUID = "uuid"
        private const val K_BOOT_ID = "boot_id"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
