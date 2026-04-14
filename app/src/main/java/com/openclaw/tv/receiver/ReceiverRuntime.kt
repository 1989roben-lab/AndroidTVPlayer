package com.openclaw.tv.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ReceiverRuntime {
    const val SERVER_PORT = 7000
    const val SSDP_PORT = 1900
    private const val MIRROR_ALIAS_PORT = 52266
    private const val MIRROR_FEATURES = "0x5A7FFFF7,0x1E"
    private const val MIRROR_MODEL = "AppleTV3,1"
    private const val MIRROR_SRCVERS = "220.68"
    private const val MIRROR_FLAGS = "0x4"

    private val started = AtomicBoolean(false)
    private var httpServer: ReceiverHttpServer? = null
    private var ssdpResponder: SsdpResponder? = null
    private var nsdManager: NsdManager? = null
    private var airPlayRegistration: NsdManager.RegistrationListener? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun isStarted(): Boolean = started.get()

    @Synchronized
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        Log.i("OpenClaw", "ReceiverRuntime.start")

        val appContext = context.applicationContext
        PlaybackManager.ensureInitialized(appContext)

        val localIp = resolveLocalIpv4Address().orEmpty()
        val deviceName = "HANG's TV"

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
        logHpplayBridgeMethods(appContext)

        httpServer = ReceiverHttpServer(appContext, localIp, SERVER_PORT).also { it.start() }
        Log.i("OpenClaw", "ReceiverRuntime.start http server started on $localIp:$SERVER_PORT")
        ClingDlnaRuntime.start(appContext)
        Log.i("OpenClaw", "ReceiverRuntime.start Cling DLNA started")
        registerAirPlayMdns(appContext, deviceName)
        registerRaopMdns(appContext, deviceName)
        Log.i("OpenClaw", "ReceiverRuntime.start AirPlay/RAOP registration requested")

        PlaybackManager.updateServiceState(
            airPlayReady = airPlayRegistration != null && raopRegistration != null,
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
            airPlayRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
            raopRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        }
        airPlayRegistration = null
        raopRegistration = null
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

    fun airPlayPublicKey(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("${airPlayDeviceId(context)}-hangs-tv".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

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
            airPlayRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        }
        airPlayRegistration = null

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp."
            port = MIRROR_ALIAS_PORT
            setAttribute("deviceid", PlaybackManager.airPlayDeviceId)
            setAttribute("features", MIRROR_FEATURES)
            setAttribute("srcvers", MIRROR_SRCVERS)
            setAttribute("flags", MIRROR_FLAGS)
            setAttribute("vv", "2")
            setAttribute("pw", "false")
            setAttribute("model", MIRROR_MODEL)
            setAttribute("pi", receiverUuid(context))
            setAttribute("pk", airPlayPublicKey(context))
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        airPlayRegistration = object : NsdManager.RegistrationListener {
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
                    airPlayReady = raopRegistration != null,
                    serviceMessage = "Receiver online on ${PlaybackManager.state.value.localAddress}:$MIRROR_ALIAS_PORT",
                    lastError = null,
                )
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayRegistration)
    }

    private fun registerRaopMdns(context: Context, deviceName: String) {
        runCatching {
            raopRegistration?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        }
        raopRegistration = null

        val raopName = airPlayDeviceId(context).replace(":", "").lowercase(Locale.US) + "@$deviceName"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = raopName
            serviceType = "_raop._tcp."
            port = MIRROR_ALIAS_PORT
            setAttribute("txtvers", "1")
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")
            setAttribute("et", "0,3,5")
            setAttribute("sv", "false")
            setAttribute("da", "true")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("pw", "false")
            setAttribute("vn", "65537")
            setAttribute("tp", "UDP")
            setAttribute("md", "0,1,2")
            setAttribute("vs", MIRROR_SRCVERS)
            setAttribute("sm", "false")
            setAttribute("ek", "1")
            setAttribute("sf", MIRROR_FLAGS)
            setAttribute("am", MIRROR_MODEL)
            setAttribute("ft", MIRROR_FEATURES)
            setAttribute("pk", airPlayPublicKey(context))
        }

        nsdManager = nsdManager ?: context.getSystemService(Context.NSD_SERVICE) as NsdManager
        raopRegistration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("OpenClaw", "RAOP registration failed errorCode=$errorCode")
                PlaybackManager.updateServiceState(
                    airPlayReady = false,
                    serviceMessage = "RAOP registration failed ($errorCode)",
                    lastError = "NsdManager failed to register RAOP service.",
                )
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i("OpenClaw", "RAOP service registered as ${serviceInfo.serviceName}")
                PlaybackManager.updateServiceState(
                    airPlayReady = airPlayRegistration != null,
                    serviceMessage = "Receiver online on ${PlaybackManager.state.value.localAddress}:$MIRROR_ALIAS_PORT",
                    lastError = null,
                )
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopRegistration)
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

    private fun logHpplayBridgeMethods(context: Context) {
        runCatching {
            val remote = context.createPackageContext(
                "com.xiaomi.mitv.smartshare",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val clazz = remote.classLoader.loadClass("com.hpplay.sdk.sink.protocol.Bridge")
            val methods = clazz.declaredMethods.joinToString { method ->
                "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
            }
            Log.i("OpenClaw", "Hpplay Bridge methods: $methods")
        }.onFailure {
            Log.w("OpenClaw", "Failed to inspect hpplay Bridge", it)
        }
    }
}
