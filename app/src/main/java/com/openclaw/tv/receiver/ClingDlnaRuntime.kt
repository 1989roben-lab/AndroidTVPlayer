package com.openclaw.tv.receiver

import android.content.Context
import android.util.Log
import com.openclaw.tv.receiver.dlna.OpenClawAVTransportService
import com.openclaw.tv.receiver.dlna.OpenClawAudioRenderingControl
import com.openclaw.tv.receiver.dlna.OpenClawConnectionManagerService
import org.fourthline.cling.UpnpService
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.android.AndroidRouter
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.meta.DeviceDetails
import org.fourthline.cling.model.meta.DeviceIdentity
import org.fourthline.cling.model.meta.LocalDevice
import org.fourthline.cling.model.meta.LocalService
import org.fourthline.cling.model.meta.ManufacturerDetails
import org.fourthline.cling.model.meta.ModelDetails
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.protocol.ProtocolFactory
import org.fourthline.cling.registry.Registry
import java.util.concurrent.atomic.AtomicBoolean

object ClingDlnaRuntime {
    private val started = AtomicBoolean(false)

    private var upnpService: UpnpService? = null
    private var localDevice: LocalDevice? = null

    @Synchronized
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        Log.i("OpenClaw", "ClingDlnaRuntime.start")

        val appContext = context.applicationContext
        val binder = AnnotationLocalServiceBinder()

        @Suppress("UNCHECKED_CAST")
        val connectionManagerService =
            binder.read(OpenClawConnectionManagerService::class.java) as LocalService<OpenClawConnectionManagerService>
        connectionManagerService.apply {
            manager = DefaultServiceManager(this, OpenClawConnectionManagerService::class.java)
        }

        @Suppress("UNCHECKED_CAST")
        val avTransportService: LocalService<OpenClawAVTransportService> =
            binder.read(OpenClawAVTransportService::class.java) as LocalService<OpenClawAVTransportService>
        avTransportService.manager = object : DefaultServiceManager<OpenClawAVTransportService>(
            avTransportService,
            OpenClawAVTransportService::class.java,
        ) {
            override fun createServiceInstance(): OpenClawAVTransportService = OpenClawAVTransportService()
        }

        @Suppress("UNCHECKED_CAST")
        val renderingService: LocalService<OpenClawAudioRenderingControl> =
            binder.read(OpenClawAudioRenderingControl::class.java) as LocalService<OpenClawAudioRenderingControl>
        renderingService.manager = object : DefaultServiceManager<OpenClawAudioRenderingControl>(
            renderingService,
            OpenClawAudioRenderingControl::class.java,
        ) {
            override fun createServiceInstance(): OpenClawAudioRenderingControl = OpenClawAudioRenderingControl()
        }

        val device = LocalDevice(
            DeviceIdentity(UDN(PlaybackManager.dlnaDeviceUuid), 1800),
            UDADeviceType("MediaRenderer", 1),
            DeviceDetails(
                PlaybackManager.currentStateSnapshot().deviceName,
                ManufacturerDetails("OpenClaw", "https://openclaw.local/"),
                ModelDetails(
                    "OpenClaw TV Receiver",
                    "OpenClaw",
                    "0.1.0",
                    "https://openclaw.local/",
                ),
            ),
            arrayOf(connectionManagerService, avTransportService, renderingService),
        )

        val service = object : UpnpServiceImpl(AndroidUpnpServiceConfiguration()) {
            override fun createRouter(protocolFactory: ProtocolFactory, registry: Registry) =
                AndroidRouter(configuration, protocolFactory, appContext)
        }
        Log.i("OpenClaw", "ClingDlnaRuntime.start adding local device ${PlaybackManager.dlnaDeviceUuid}")
        service.registry.addDevice(device)
        Log.i("OpenClaw", "ClingDlnaRuntime.start local device registered")

        upnpService = service
        localDevice = device

        PlaybackManager.updateServiceState(
            dlnaReady = true,
            serviceMessage = "Receiver online on ${PlaybackManager.currentStateSnapshot().localAddress}:${ReceiverRuntime.SERVER_PORT}",
            lastError = null,
        )
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        Log.i("OpenClaw", "ClingDlnaRuntime.stop")

        localDevice?.let { device ->
            upnpService?.registry?.removeDevice(device)
        }
        localDevice = null

        upnpService?.shutdown()
        upnpService = null
    }
}
