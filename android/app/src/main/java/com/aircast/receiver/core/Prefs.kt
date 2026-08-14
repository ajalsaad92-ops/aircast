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

    /** Empty means anyone on the LAN may start a mirroring session. */
    var pinCode: String
        get() = sp.getString(K_PIN, "") ?: ""
        set(v) = sp.edit().putString(K_PIN, v.filter { it.isDigit() }.take(6)).apply()

    /**
     * AirPlay senders access control — mirrors AirScreen's `AirPlay security`.
     *   off      — anyone on the LAN may start playback (legacy behaviour).
     *   code     — a 4-digit code is shown on this device's screen (regenerated
     *              every ten minutes); the sender must enter it before playback
     *              begins. Falls back to the PIN when one is configured.
     *   password — the PIN configured in settings gates playback.
     */
    var airplaySecurityMode: String
        get() = sp.getString(K_AIRPLAY_SECURITY, "off") ?: "off"
        set(v) = sp.edit().putString(K_AIRPLAY_SECURITY, v).apply()

    /**
     * Cast connection access control — mirrors AirScreen's `Cast security`.
     *   off — accept every new sender immediately.
     *   ask — hold new senders until the user accepts or rejects them in the
     *         UI; acceptance can be one-time or permanent ("always trust").
     */
    var castSecurityMode: String
        get() = sp.getString(K_CAST_SECURITY, "off") ?: "off"
        set(v) = sp.edit().putString(K_CAST_SECURITY, v).apply()

    /** Persistent set of Cast peers the user marked as "always trust". */
    fun castTrustedPeers(): Set<String> =
        sp.getStringSet(K_CAST_TRUSTED, null)?.toHashSet() ?: emptySet()

    fun castTrustedPeers(peers: Set<String>) =
        sp.edit().putStringSet(K_CAST_TRUSTED, peers.toSet()).apply()

    /**
     * Upper bound for simultaneous sending devices (0 = unlimited, AirScreen
     * defaults to one active session and warns when a second device joins).
     */
    var multiDeviceMax: Int
        get() = sp.getInt(K_MULTI_MAX, 0)
        set(v) = sp.edit().putInt(K_MULTI_MAX, v).apply()

    /**
     * Off-screen behaviour while the receiver is on:
     *   `off`    — nothing; the notification and the service keep running.
     *   `canvas` — a floating animated overlay (SYSTEM_ALERT_WINDOW) proves the
     *              receiver is alive over the home screen — the same trick
     *              AirScreen uses as its background screensaver.
     */
    var backgroundMode: String
        get() = sp.getString(K_BG_MODE, "off") ?: "off"
        set(v) = sp.edit().putString(K_BG_MODE, v).apply()

    /** `auto` / `horizontal` / `vertical`: applied to the player window at load time. */
    var forcedRotation: String
        get() = sp.getString(K_ROTATION, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_ROTATION, v).apply()

    /** `native` / `720p` / `1080p` / `4k`: caps the video renderer output size. */
    var screenResolution: String
        get() = sp.getString(K_RESOLUTION, "native") ?: "native"
        set(v) = sp.edit().putString(K_RESOLUTION, v).apply()

    /** Keep decoding when the screen locks instead of letting playback stall. */
    var keepPlaying: Boolean
        get() = sp.getBoolean(K_KEEP_PLAYING, false)
        set(v) = sp.edit().putBoolean(K_KEEP_PLAYING, v).apply()

    /** Auto-lower mirror quality when the link looks congested. */
    var smartVideoQuality: Boolean
        get() = sp.getBoolean(K_SMART_QUALITY, false)
        set(v) = sp.edit().putBoolean(K_SMART_QUALITY, v).apply()

    /**
     * SMB network media browser: `enabled` toggles the /browse endpoint and
     * `servers` is a JSON array of `{name, host, share, user, pass}` entries.
     */
    var smbEnabled: Boolean
        get() = sp.getBoolean(K_SMB, false)
        set(v) = sp.edit().putBoolean(K_SMB, v).apply()

    var smbServers: String
        get() = sp.getString(K_SMB_SERVERS, "[]") ?: "[]"
        set(v) = sp.edit().putString(K_SMB_SERVERS, v).apply()

    /** Google Cast receiver app id registered in the Cast developer console. */
    var castAppId: String
        get() = sp.getString(K_CAST_APP_ID, "") ?: ""
        set(v) = sp.edit().putString(K_CAST_APP_ID, v.trim()).apply()

    var castEnabled: Boolean
        get() = sp.getBoolean(K_CAST_ON, true)
        set(v) = sp.edit().putBoolean(K_CAST_ON, v).apply()

    /**
     * Cast device-auth bypass — mirrors AirScreen's behaviour for personal receivers.
     * When `true`, the receiver does not rely on the DEVICE_AUTH replay certificate to
     * satisfy strict senders (such as Meta Quest Chromecast mode without bypass).
     * Senders that proceed without verifying the certificate will work as-is; senders
     * that demand a valid chain are handled by CastAuth's embedded replay tuple.
     */
    var castBypassAuth: Boolean
        get() = sp.getBoolean(K_CAST_BYPASS, false)
        set(v) = sp.edit().putBoolean(K_CAST_BYPASS, v).apply()

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

    /**
     * Preferred cap for the mirroring track: 720 / 1080 / 1440 / 2160 (0 = Auto).
     * Defaults to Auto: capture at the source's native resolution and let WebRTC
     * scale down automatically for slower devices/links.
     */
    var mirrorQuality: Int
        get() = sp.getInt(K_QUALITY, 0)
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
        .put("pinCode", pinCode)
        .put("autoStart", autoStart)
        .put("keepScreenOn", keepScreenOn)
        .put("recordAudio", recordAudio)
        .put("language", language)
        .put("mirrorQuality", mirrorQuality)
        .put("httpPort", httpPort)
        .put("httpsPort", httpsPort)
        .put("airplayPort", airplayPort)
        .put("airplaySecurityMode", airplaySecurityMode)
        .put("castSecurityMode", castSecurityMode)
        .put("castTrustedPeers", org.json.JSONArray().also { arr -> castTrustedPeers().forEach { arr.put(it) } })
        .put("multiDeviceMax", multiDeviceMax)
        .put("backgroundMode", backgroundMode)
        .put("forcedRotation", forcedRotation)
        .put("screenResolution", screenResolution)
        .put("keepPlaying", keepPlaying)
        .put("smartVideoQuality", smartVideoQuality)
        .put("smbEnabled", smbEnabled)
        .put("smbServers", smbServers)
        .put("castAppId", castAppId)
        .put("castEnabled", castEnabled)
        .put("castBypassAuth", castBypassAuth)

    fun applyJson(o: JSONObject) {
        if (o.has("deviceName")) deviceName = o.optString("deviceName")
        if (o.has("dlnaEnabled")) dlnaEnabled = o.optBoolean("dlnaEnabled", true)
        if (o.has("airplayEnabled")) airplayEnabled = o.optBoolean("airplayEnabled", true)
        if (o.has("mirrorEnabled")) mirrorEnabled = o.optBoolean("mirrorEnabled", true)
        if (o.has("pinCode")) pinCode = o.optString("pinCode")
        if (o.has("autoStart")) autoStart = o.optBoolean("autoStart", true)
        if (o.has("keepScreenOn")) keepScreenOn = o.optBoolean("keepScreenOn", true)
        if (o.has("recordAudio")) recordAudio = o.optBoolean("recordAudio", true)
        if (o.has("language")) language = o.optString("language", "ar")
        if (o.has("mirrorQuality")) mirrorQuality = o.optInt("mirrorQuality", 0)
        if (o.has("airplaySecurityMode")) airplaySecurityMode = o.optString("airplaySecurityMode", "off")
        if (o.has("castSecurityMode")) castSecurityMode = o.optString("castSecurityMode", "off")
        if (o.has("multiDeviceMax")) multiDeviceMax = o.optInt("multiDeviceMax", 0)
        if (o.has("backgroundMode")) backgroundMode = o.optString("backgroundMode", "off")
        if (o.has("forcedRotation")) forcedRotation = o.optString("forcedRotation", "auto")
        if (o.has("screenResolution")) screenResolution = o.optString("screenResolution", "native")
        if (o.has("keepPlaying")) keepPlaying = o.optBoolean("keepPlaying", false)
        if (o.has("smartVideoQuality")) smartVideoQuality = o.optBoolean("smartVideoQuality", false)
        if (o.has("smbEnabled")) smbEnabled = o.optBoolean("smbEnabled", false)
        if (o.has("smbServers")) smbServers = o.optString("smbServers", "[]")
        if (o.has("castAppId")) castAppId = o.optString("castAppId", "")
        if (o.has("castEnabled")) castEnabled = o.optBoolean("castEnabled", true)
        if (o.has("castBypassAuth")) castBypassAuth = o.optBoolean("castBypassAuth", false)
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 8321
        const val DEFAULT_HTTPS_PORT = 8322
        const val DEFAULT_AIRPLAY_PORT = 7000

        private const val K_NAME = "device_name"
        private const val K_DLNA = "dlna_enabled"
        private const val K_AIRPLAY = "airplay_enabled"
        private const val K_MIRROR = "mirror_enabled"
        private const val K_PIN = "pin_code"
        private const val K_AUTOSTART = "auto_start"
        private const val K_AWAKE = "keep_screen_on"
        private const val K_REC_AUDIO = "record_audio"
        private const val K_LANG = "language"
        private const val K_QUALITY = "mirror_quality"
        private const val K_HTTP_PORT = "http_port"
        private const val K_HTTPS_PORT = "https_port"
        private const val K_AIRPLAY_PORT = "airplay_port"
        private const val K_AIRPLAY_SECURITY = "airplay_security"
        private const val K_CAST_SECURITY = "cast_security"
        private const val K_CAST_TRUSTED = "cast_trusted_peers"
        private const val K_MULTI_MAX = "multi_device_max"
        private const val K_BG_MODE = "background_mode"
        private const val K_ROTATION = "forced_rotation"
        private const val K_RESOLUTION = "screen_resolution"
        private const val K_KEEP_PLAYING = "keep_playing"
        private const val K_SMART_QUALITY = "smart_quality"
        private const val K_SMB = "smb_enabled"
        private const val K_SMB_SERVERS = "smb_servers"
        private const val K_CAST_APP_ID = "cast_app_id"
        private const val K_CAST_ON = "cast_on"
        private const val K_CAST_BYPASS = "cast_bypass_auth"
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
