package com.aircast.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Prefs

/**
 * Brings the receiver back after a reboot. A TV box is expected to be discoverable the
 * moment it is powered on, without anyone opening the app first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.get(context).autoStart) return
        Logger.i("boot", "auto-start enabled; bringing the receiver online")
        try {
            ReceiverService.start(context.applicationContext)
        } catch (e: Exception) {
            Logger.e("boot", "auto-start failed: ${e.message}")
        }
    }
}
