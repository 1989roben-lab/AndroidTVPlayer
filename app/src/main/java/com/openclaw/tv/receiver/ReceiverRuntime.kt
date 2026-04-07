package com.openclaw.tv.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ReceiverRuntime {
    const val SERVER_PORT = 7000
    const val SSDP_PORT = 1900

    private val started = AtomicBoolean(false)
    private var httpServer: ReceiverHttpServer? = null
    private var ssdpResponder: SsdpResponder? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun isStarted(): Boolean = started.get()

    @Synchronized
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        Log.i("OpenClaw", "ReceiverRuntime.start")

        val appContext = context.applicationContext
        PlaybackManager.ensureInitialized(appContext)

        val localIp = resolveLocalIpv4Address().orEmpty()
        val deviceName = "OpenClaw TV"

        if (localIp.isBlank()) {
            Log.w("OpenClaw", "ReceiverRuntime.start no local IPv4 address")
            PlaybackManager.updateServiceState(
                airPlayReady = false,
                dlnaReady = false,
                localAddress = "",
                serviceMessage = "Receiver waiting for a usable LAN address",
                lastError = "Could not resolve a local IPv4 address.",
            )
            started.set(false)
            return
        }

        acquireMulticastLock(appContext)
        Log.i("OpenClaw", "ReceiverRuntime.start localIp=$localIp")

        httpServer = ReceiverHttpServer(appContext, localIp, SERVER_PORT).also { it.start() }
        Log.i("OpenClaw", "ReceiverRuntime.start http server started on $localIp:$SERVER_PORT")
        ClingDlnaRuntime.start(appContext)
        Log.i("OpenClaw", "ReceiverRuntime.start Cling DLNA started")
        registerAirPlayMdns(appContext, deviceName)
        Log.i("OpenClaw", "ReceiverRuntime.start AirPlay registration requested")

        PlaybackManager.updateServiceState(
            airPlayReady = nsdRegistration != null,
            dlnaReady = true,
            localAddress = localIp,
            serviceMessage = "Receiver online on $localIp:$SERVER_PORT",
            lastError = null,
        )
    }

    @Synchronized
    fun refresh(context: Context) {
        Log.i("OpenClaw", "ReceiverRuntime.refresh")
        stop()
        start(context)
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        Log.i("OpenClaw", "ReceiverRuntime.stop")

        ssdpResponder?.shutdown()
        ssdpResponder = null

        ClingDlnaRuntime.stop()

        httpServer?.stop()
        httpServer = null

        runCatching {
            nsdRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        }
        nsdRegistration = null
        nsdManager = null

        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    fun airPlayDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val seed = androidId ?: UUID.randomUUID().toString()
        val hex = seed.filter { it.isLetterOrDigit() }.uppercase(Locale.US).padEnd(12, '0').take(12)
        return hex.chunked(2).joinToString(":")
    }

    fun receiverUuid(context: Context): String =
        UUID.nameUUIDFromBytes(airPlayDeviceId(context).toByteArray()).toString()

    private fun acquireMulticastLock(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("openclaw-receiver").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i("OpenClaw", "ReceiverRuntime.acquireMulticastLock")
    }

    private fun registerAirPlayMdns(context: Context, deviceName: String) {
        runCatching {
            nsdRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        }
        nsdRegistration = null

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp."
            port = SERVER_PORT
            setAttribute("deviceid", PlaybackManager.airPlayDeviceId)
            setAttribute("features", "0x2233")
            setAttribute("model", "AppleTV3,2")
            setAttribute("srcvers", "220.68")
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdRegistration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("OpenClaw", "AirPlay registration failed errorCode=$errorCode")
                PlaybackManager.updateServiceState(
                    airPlayReady = false,
                    serviceMessage = "AirPlay registration failed ($errorCode)",
                    lastError = "NsdManager failed to register AirPlay service.",
                )
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i("OpenClaw", "AirPlay service registered as ${serviceInfo.serviceName}")
                PlaybackManager.updateServiceState(
                    airPlayReady = true,
                    serviceMessage = "Receiver online on ${PlaybackManager.state.value.localAddress}:$SERVER_PORT",
                    lastError = null,
                )
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistration)
    }

    private fun resolveLocalIpv4Address(): String? {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            networkInterface.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    Log.i("OpenClaw", "ReceiverRuntime.resolveLocalIpv4Address using ${networkInterface.name}=${address.hostAddress}")
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
