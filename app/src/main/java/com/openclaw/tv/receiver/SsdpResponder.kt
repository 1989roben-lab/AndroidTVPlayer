package com.openclaw.tv.receiver

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SsdpResponder(
    private val localIp: String,
    private val httpPort: Int,
    private val deviceName: String,
) {
    private val multicastAddress = InetAddress.getByName("239.255.255.250")
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "openclaw-ssdp", isDaemon = true) {
            try {
                socket = MulticastSocket(ReceiverRuntime.SSDP_PORT).apply {
                    reuseAddress = true
                    joinGroup(multicastAddress)
                }
                advertiseAlive()

                val buffer = ByteArray(2048)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val payload = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val searchTarget = payload.lineSequence()
                        .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.lowercase(Locale.US)

                    if (payload.contains("M-SEARCH", ignoreCase = true) && searchTarget != null) {
                        matchingSearchTargets(searchTarget).forEach { target ->
                            respond(packet.address, packet.port, target)
                        }
                    }
                }
            } catch (error: SocketException) {
                if (running.get()) throw error
            } finally {
                socket?.runCatching { close() }
                socket = null
            }
        }
    }

    fun shutdown() {
        running.set(false)
        runCatching { advertiseByebye() }
        socket?.close()
        socket = null
    }

    private fun respond(address: InetAddress, port: Int, target: String) {
        val location = "http://$localIp:$httpPort/dlna/device.xml"
        val payload = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=120
            DATE: ${Date()}
            EXT:
            LOCATION: $location
            SERVER: Android/6.0.1 UPnP/1.0 OpenClaw/0.1
            ST: $target
            USN: ${usnFor(target)}
            X-USER-AGENT: $deviceName
            
        """.trimIndent().replace("\n", "\r\n")

        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        socket?.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    private fun advertiseAlive() {
        notify("ssdp:alive")
    }

    private fun advertiseByebye() {
        notify("ssdp:byebye")
    }

    private fun notify(notificationType: String) {
        val location = "http://$localIp:$httpPort/dlna/device.xml"
        notifyTargets().forEach { target ->
            val payload = """
                NOTIFY * HTTP/1.1
                HOST: 239.255.255.250:1900
                CACHE-CONTROL: max-age=120
                LOCATION: $location
                SERVER: Android/6.0.1 UPnP/1.0 OpenClaw/0.1
                NT: $target
                NTS: $notificationType
                USN: ${usnFor(target)}
                X-USER-AGENT: $deviceName
                
            """.trimIndent().replace("\n", "\r\n")
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            socket?.send(DatagramPacket(bytes, bytes.size, multicastAddress, ReceiverRuntime.SSDP_PORT))
        }
    }

    private fun matchingSearchTargets(searchTarget: String): List<String> = when (searchTarget) {
        "ssdp:all" -> notifyTargets()
        "upnp:rootdevice" -> listOf("upnp:rootdevice")
        "uuid:${deviceUuid()}" -> listOf("uuid:${deviceUuid()}")
        "urn:schemas-upnp-org:device:mediarenderer:1" -> listOf("urn:schemas-upnp-org:device:MediaRenderer:1")
        "urn:schemas-upnp-org:service:avtransport:1" -> listOf("urn:schemas-upnp-org:service:AVTransport:1")
        "urn:schemas-upnp-org:service:renderingcontrol:1" -> listOf("urn:schemas-upnp-org:service:RenderingControl:1")
        "urn:schemas-upnp-org:service:connectionmanager:1" -> listOf("urn:schemas-upnp-org:service:ConnectionManager:1")
        else -> emptyList()
    }

    private fun notifyTargets(): List<String> = listOf(
        "upnp:rootdevice",
        "uuid:${deviceUuid()}",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
    )

    private fun usnFor(target: String): String = when (target) {
        "uuid:${deviceUuid()}" -> target
        else -> "uuid:${deviceUuid()}::$target"
    }

    private fun deviceUuid(): String = PlaybackManager.dlnaDeviceUuid
}
