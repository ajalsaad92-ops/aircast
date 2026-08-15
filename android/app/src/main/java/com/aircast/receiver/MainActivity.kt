package com.aircast.receiver

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.service.ReceiverService
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    companion object {
        private val launchStartedMs = System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        val provisioned = intent?.getStringExtra("cast_app_id")
        if (!provisioned.isNullOrBlank()) {
            val p = Prefs.get(this)
            if (p.castAppId != provisioned.trim()) {
                p.castAppId = provisioned.trim()
                com.aircast.receiver.core.Logger.i("main", "cast app id provisioned via intent: ${p.castAppId}")
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
        if (!provisioned.isNullOrBlank()) {
            val p = Prefs.get(this)
            if (p.castAppId != provisioned.trim()) {
                p.castAppId = provisioned.trim()
                com.aircast.receiver.core.Logger.i("main", "cast app id provisioned via intent (new): ${p.castAppId}")
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
}
