package com.aircast.receiver

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.service.ReceiverService
import com.getcapacitor.BridgeActivity
import java.io.File

class MainActivity : BridgeActivity() {
    companion object {
        private val launchStartedMs = System.currentTimeMillis()
        /** Built-in registered Google Cast app id — always on, no manual setup. */
        const val DEFAULT_CAST_APP_ID = "02898B6E"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Record the real cause of any crash (incl. background-thread exceptions that
        // method-level try/catch cannot catch) to logcat + a file.
        installCrashLogger()

        // If a previous run crashed, show the captured trace on-screen instead of
        // re-running the code that crashed — so the cause is visible on the phone.
        val crashFile = File(getExternalFilesDir(null), "aircast-crash.txt")
        if (crashFile.exists()) {
            showCrashReport(savedInstanceState, crashFile)
            return
        }

        // Core splash screen on Android 16 crashes with an IllegalStateException
        // unless `setKeepOnScreenCondition` is called, so keep it up briefly while
        // the bridge warms up and drop it afterwards.
        installSplashScreen().setKeepOnScreenCondition {
            System.currentTimeMillis() - launchStartedMs < 900
        }
        // Registration has to happen before super.onCreate(), which builds the bridge.
        registerPlugin(AirCastPlugin::class.java)
        super.onCreate(savedInstanceState)

        // Allow provisioning the Google Cast app id from adb without touching the UI:
        //   adb shell am start -n com.aircast.receiver.debug/... --es cast_app_id 02898B6E
        val rawProvisioned = intent?.getStringExtra("cast_app_id")
        // Default built-in registration: this app always runs with its own
        // registered Cast app id (02898B6E) and device-auth bypass, so the user
        // never has to provision anything manually.
        val provisioned = if (rawProvisioned.isNullOrBlank()) DEFAULT_CAST_APP_ID else rawProvisioned
        run {
            val p = Prefs.get(this)
            val wantId = provisioned?.trim().orEmpty()
            if (p.castAppId != wantId) {
                p.castAppId = wantId
                com.aircast.receiver.core.Logger.i("main", "cast app id set (intent=${!provisioned.isNullOrBlank()}): ${p.castAppId}")
            }
            if (!p.castBypassAuth) {
                p.castBypassAuth = true
                com.aircast.receiver.core.Logger.i("main", "cast device-auth bypass enabled by default")
            }
            if (!p.castEnabled) {
                p.castEnabled = true
                com.aircast.receiver.core.Logger.i("main", "cast protocol enabled by default")
            }
        }
        // Provision device-auth bypass from adb so Quest 3 (Chromecast mode with
        // Bypass Device Auth) can connect without touching the UI:
        //   adb shell am start -n com.aircast.receiver.debug/... --ez cast_bypass_auth true
        val bypassExtra = intent?.getBooleanExtra("cast_bypass_auth", false)
        if (intent?.hasExtra("cast_bypass_auth") == true) {
            val p = Prefs.get(this)
            if (p.castBypassAuth != bypassExtra) {
                p.castBypassAuth = bypassExtra ?: false
                com.aircast.receiver.core.Logger.i("main", "cast bypass auth provisioned via intent: ${p.castBypassAuth}")
            }
        }
        // Same provisioning path for the advertised device name so it shows
        // correctly in cast lists without touching the UI:
        //   adb shell am start -n ... --es device_name "AirCast (Galaxy S24)"
        val provisionedName = intent?.getStringExtra("device_name")
        if (!provisionedName.isNullOrBlank()) {
            val p = Prefs.get(this)
            if (p.deviceName != provisionedName.trim()) {
                p.deviceName = provisionedName.trim()
                com.aircast.receiver.core.Logger.i("main", "device name provisioned via intent: ${p.deviceName}")
            }
        }
        // Opening the app is itself a signal that the user wants to be discoverable, so
        // the receiver comes up without making them press anything — matching how every
        // TV-box receiver behaves.
        if (Prefs.get(this).autoStart && !ReceiverService.isRunning) {
            try {
                ReceiverService.start(applicationContext)
            } catch (e: Exception) {
                // Service start may fail on some OEM / API-level combinations
                // (e.g. foreground-service policy on Android 16); the app must
                // still open even when the background receiver cannot come up.
                com.aircast.receiver.core.Logger.e("main", "receiver start failed: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // SingleTask launch: when the app is already running, delivery lands here
        // instead of onCreate — so re-run the provisioning logic.
        val provisioned = intent.getStringExtra("cast_app_id")
        val wantId = if (!provisioned.isNullOrBlank()) provisioned.trim() else DEFAULT_CAST_APP_ID
        run {
            val p = Prefs.get(this)
            if (p.castAppId != wantId) {
                p.castAppId = wantId
                com.aircast.receiver.core.Logger.i("main", "cast app id set via new intent (intent=${!provisioned.isNullOrBlank()}): ${p.castAppId}")
            }
            if (!p.castBypassAuth) {
                p.castBypassAuth = true
                com.aircast.receiver.core.Logger.i("main", "cast device-auth bypass enabled by default (new intent)")
            }
            if (!p.castEnabled) {
                p.castEnabled = true
                com.aircast.receiver.core.Logger.i("main", "cast protocol enabled by default (new intent)")
            }
        }
        if (intent.hasExtra("cast_bypass_auth")) {
            val p = Prefs.get(this)
            if (p.castBypassAuth != intent.getBooleanExtra("cast_bypass_auth", false)) {
                p.castBypassAuth = intent.getBooleanExtra("cast_bypass_auth", false)
                com.aircast.receiver.core.Logger.i("main", "cast bypass auth provisioned via intent (new): ${p.castBypassAuth}")
            }
        }
        val provisionedName = intent.getStringExtra("device_name")
        if (!provisionedName.isNullOrBlank()) {
            val p = Prefs.get(this)
            if (p.deviceName != provisionedName.trim()) {
                p.deviceName = provisionedName.trim()
                com.aircast.receiver.core.Logger.i("main", "device name provisioned via intent (new): ${p.deviceName}")
            }
        }
    }

    /** Renders the last captured crash trace full-screen so it can be screenshotted. */
    private fun showCrashReport(savedInstanceState: Bundle?, crashFile: File) {
        try {
            super.onCreate(savedInstanceState)
        } catch (t: Throwable) {
            android.util.Log.e("AIRCAST_CRASH", "recovery bridge init failed: ${t.message}")
        }
        val trace = try { crashFile.readText() } catch (e: Throwable) { "read error: ${e.message}" }
        val tv = TextView(this).apply {
            text = "AirCast crash report — screenshot & send this:\n\n$trace"
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(28, 56, 28, 28)
            setTextColor(0xFFE8EEFC.toInt())
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(0xFF0E1015.toInt())
            addView(tv)
        }
        setContentView(sv)
    }

    /** Global handler: writes any uncaught exception's full stack trace before the app dies. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                val trace = sw.toString()
                android.util.Log.e("AIRCAST_CRASH", "Uncaught on '${thread.name}':\n$trace")
                try {
                    getExternalFilesDir(null)?.let { dir ->
                        File(dir, "aircast-crash.txt").writeText("thread=${thread.name}\n\n$trace")
                    }
                } catch (_: Throwable) { /* best effort */ }
            } catch (_: Throwable) { /* never crash the crash handler */ }
            previous?.uncaughtException(thread, ex)
        }
    }
}
