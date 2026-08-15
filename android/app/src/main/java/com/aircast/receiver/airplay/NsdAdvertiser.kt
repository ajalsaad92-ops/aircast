package com.aircast.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Net
import com.aircast.receiver.core.Prefs

/**
 * Publishes the Bonjour/mDNS records Apple devices look for.
 *
 * `_airplay._tcp` is what makes the AirPlay icon appear in iOS Control Center and in
 * Safari/YouTube's route picker; `_raop._tcp` is the audio (AirTunes) side, whose
 * instance name must be `<12-hex-deviceid>@<friendly name>` or clients ignore it.
 *
 * Note on scope: this advertises and answers the *unencrypted* AirPlay endpoints.
 * Screen mirroring additionally requires Apple's FairPlay (SAP) key exchange, which is
 * not implemented here — see README, "What is intentionally not implemented".
 */
class NsdAdvertiser(private val context: Context) {

    private val prefs = Prefs.get(context)
    private var nsd: NsdManager? = null
    private val registered = ArrayList<NsdManager.RegistrationListener>()

    fun start(airplayPort: Int, httpPort: Int) {
        stop()
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Logger.w("mdns", "NsdManager unavailable on this device")
            return
        }
        nsd = manager

        val deviceId = Net.deviceId(context)
        val name = prefs.deviceName

        register(
            manager,
            serviceName = name,
            type = "_airplay._tcp",
            port = airplayPort,
            attributes = airplayTxt(deviceId),
        )

        register(
            manager,
            serviceName = "$deviceId@$name",
            type = "_raop._tcp",
            port = airplayPort,
            attributes = raopTxt(),
        )

        // Advertise as a Google Cast receiver so a Quest / Chrome sender discovers
        // AirCast in its cast list. Selection still passes through device auth in
        // CastReceiver (the CastAuth replay); on a Quest with "Bypass Device Auth"
        // enabled the session proceeds to the WebRTC OFFER and CastMirrorActivity shows
        // the stream. Without bypass the sender may still hang up at authentication.
        register(
            manager,
            serviceName = name,
            type = "_googlecast._tcp",
            port = com.aircast.receiver.cast.CastV2.PORT,
            attributes = mapOf(
                "id" to castDeviceId(),
                "ve" to "05",
                "md" to "AirCast Receiver",
                "ic" to "/setup/icon.png",
                "fn" to name,
                "ca" to "2136",
                "st" to "0",
                "rm" to name,
                "rs" to "",
                "nf" to "0",
            ),
        )

        // Our own record, used by the companion sender page to find the box by name.
        register(
            manager,
            serviceName = name,
            type = "_aircast._tcp",
            port = httpPort,
            attributes = mapOf(
                "ver" to "1.0.0",
                "id" to deviceId,
                "http" to httpPort.toString(),
                "model" to (Build.MODEL ?: "Android"),
            ),
        )
    }

    fun stop() {
        val manager = nsd ?: return
        for (listener in registered) {
            try {
                manager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
        registered.clear()
        nsd = null
    }

    private fun register(
        manager: NsdManager,
        serviceName: String,
        type: String,
        port: Int,
        attributes: Map<String, String>,
    ) {
        try {
            val info = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = type
                this.port = port
            }
            for ((k, v) in attributes) {
                try {
                    info.setAttribute(k, v)
                } catch (_: Exception) {
                    // Some OEM stacks reject long TXT values; the record is still useful without them.
                }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Logger.i("mdns", "advertised $type as \"${info.serviceName}\" on port $port")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Logger.w("mdns", "failed to advertise $type (error $errorCode)")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Logger.i("mdns", "withdrew $type")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registered.add(listener)
        } catch (e: Exception) {
            Logger.e("mdns", "register $type failed: ${e.message}")
        }
    }

    /**
     * `features` is a bitfield; the value below advertises video, photo, slideshow and
     * playback-control support while leaving the mirroring and FairPlay bits clear, so
     * senders do not offer a mode we cannot honour.
     */
    private fun airplayTxt(deviceId: String) = mapOf(
        "deviceid" to deviceId.chunked(2).joinToString(":"),
        "features" to "0x77",
        "model" to "AppleTV3,2",
        "srcvers" to "220.68",
        "vv" to "2",
        "flags" to "0x4",
        "pk" to "",
        "pi" to Net.uuid(context),
    )

    /** 32 hex digits, the shape senders expect in the Cast `id` record. */
    private fun castDeviceId(): String {
        val uuid = Net.uuid(context).replace("-", "")
        return (uuid + uuid).take(32).lowercase()
    }

    private fun raopTxt() = mapOf(
        "txtvers" to "1",
        "ch" to "2",
        "cn" to "0,1",
        "et" to "0,1",
        "sv" to "false",
        "da" to "true",
        "sr" to "44100",
        "ss" to "16",
        "pw" to "false",
        "vn" to "3",
        "tp" to "UDP",
        "vs" to "220.68",
        "am" to "AppleTV3,2",
        "sf" to "0x4",
    )
}
