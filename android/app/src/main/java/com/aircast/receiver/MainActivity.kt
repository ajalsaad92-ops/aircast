package com.aircast.receiver

import android.os.Bundle
import com.aircast.receiver.core.Prefs
import com.aircast.receiver.service.ReceiverService
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Registration has to happen before super.onCreate(), which builds the bridge.
        registerPlugin(AirCastPlugin::class.java)
        super.onCreate(savedInstanceState)

        // Opening the app is itself a signal that the user wants to be discoverable, so
        // the receiver comes up without making them press anything — matching how every
        // TV-box receiver behaves.
        if (Prefs.get(this).autoStart && !ReceiverService.isRunning) {
            ReceiverService.start(applicationContext)
        }
    }
}
