package com.openclaw.tv.receiver

import android.content.Context
import android.util.Log
import com.dd.plist.NSObject
import com.dd.plist.PropertyListParser
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class ReceiverHttpServer(
    private val context: Context,
    private val localIp: String,
    port: Int,
) : NanoHTTPD(port) {
    private companion object {
        private const val TAG = "OpenClawHttp"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            Log.d(TAG, "request ${session.method} ${session.uri}")
            when {
                session.uri == "/server-info" && session.method == Method.GET -> xmlResponse(buildAirPlayServerInfo())
                session.uri == "/play" && session.method == Method.POST -> handleAirPlayPlay(session)
                session.uri == "/photo" && session.method == Method.PUT -> handleAirPlayPhoto(session)
                session.uri == "/stop" && session.method == Method.POST -> emptyOk { PlaybackManager.stop() }
                session.uri == "/rate" && session.method == Method.POST -> emptyOk { handleRate(session) }
                session.uri == "/scrub" && session.method == Method.POST -> emptyOk { handleScrub(session) }
                session.uri == "/scrub" && session.method == Method.GET -> textParametersResponse(
                    "duration: ${PlaybackManager.state.value.durationMs / 1000.0}\nposition: ${PlaybackManager.state.value.positionMs / 1000.0}\n",
                )
                session.uri == "/playback-info" && session.method == Method.GET -> xmlResponse(buildPlaybackInfo())
                session.uri == "/slideshow-features" && session.method == Method.GET -> xmlResponse(emptyPlist())
                session.uri == "/reverse" && session.method == Method.POST -> newFixedLengthResponse(Response.Status.SWITCH_PROTOCOL, null, "").apply {
                    addHeader("Connection", "Upgrade")
                    addHeader("Upgrade", "PTTH/1.0")
                }
                session.uri == "/dlna/device.xml" && session.method == Method.GET -> xmlResponse(buildDeviceDescription())
                session.uri == "/dlna/service/avtransport.xml" && session.method == Method.GET -> xmlResponse(avTransportScpd())
                session.uri == "/dlna/service/rendering.xml" && session.method == Method.GET -> xmlResponse(renderingControlScpd())
                session.uri == "/dlna/service/connection.xml" && session.method == Method.GET -> xmlResponse(connectionManagerScpd())
                session.uri == "/dlna/control/avtransport" && session.method == Method.POST -> soapResponse(handleAvTransport(session))
                session.uri == "/dlna/control/rendering" && session.method == Method.POST -> soapResponse(handleRenderingControl(session))
                session.uri == "/dlna/control/connection" && session.method == Method.POST -> soapResponse(handleConnectionManager(session))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (t: Throwable) {
            PlaybackManager.updateServiceState(lastError = t.message, serviceMessage = "Receiver request failed")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", t.stackTraceToString())
        }
    }

    private fun handleAirPlayPlay(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = if (session.headers["content-type"]?.contains("application/x-apple-binary-plist", true) == true) {
            buildRequestFromPlist(body)
        } else {
            buildRequestFromParameters(body.toString(StandardCharsets.UTF_8))
        }

        PlaybackManager.play(request, ReceiverProtocol.AirPlay)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    }

    private fun handleAirPlayPhoto(session: IHTTPSession): Response {
        PlaybackManager.displayPhotoBytes(readBody(session), ReceiverProtocol.AirPlay)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    }

    private fun handleRate(session: IHTTPSession) {
        val value = session.parameters["value"]?.firstOrNull()?.toFloatOrNull() ?: 1f
        if (value <= 0f) PlaybackManager.pause() else PlaybackManager.resume()
    }

    private fun handleScrub(session: IHTTPSession) {
        val seconds = session.parameters["position"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        PlaybackManager.seekTo((seconds * 1000).toLong())
    }

    private fun handleAvTransport(session: IHTTPSession): String {
        val action = session.headers["soapaction"]
            ?.substringAfter("#")
            ?.substringBefore("\"")
            ?.trim()
            .orEmpty()
        val body = readBody(session).toString(StandardCharsets.UTF_8)

        return when (action) {
            "SetAVTransportURI" -> {
                val uri = extractXmlTag(body, "CurrentURI").orEmpty()
                val meta = extractXmlTag(body, "CurrentURIMetaData")
                PlaybackManager.prepareDlnaRequest(
                    ReceiverPlaybackRequest(
                        uri = htmlDecode(uri),
                        title = parseDlnaTitle(meta),
                        mimeType = parseDlnaMimeType(meta),
                    ),
                )
                dlnaActionResponse("u:SetAVTransportURIResponse")
            }
            "Play" -> {
                PlaybackManager.playPreparedDlnaRequest()
                dlnaActionResponse("u:PlayResponse")
            }
            "Pause" -> {
                PlaybackManager.pause()
                dlnaActionResponse("u:PauseResponse")
            }
            "Stop" -> {
                PlaybackManager.stop()
                dlnaActionResponse("u:StopResponse")
            }
            "Seek" -> {
                val target = extractXmlTag(body, "Target").orEmpty()
                PlaybackManager.seekTo(parseDlnaDurationMs(target))
                dlnaActionResponse("u:SeekResponse")
            }
            "GetTransportInfo" -> dlnaActionResponse(
                "u:GetTransportInfoResponse",
                """
                <CurrentTransportState>${transportState()}</CurrentTransportState>
                <CurrentTransportStatus>OK</CurrentTransportStatus>
                <CurrentSpeed>1</CurrentSpeed>
                """.trimIndent(),
            )
            "GetPositionInfo" -> dlnaActionResponse(
                "u:GetPositionInfoResponse",
                """
                <Track>1</Track>
                <TrackDuration>${formatDlnaDuration(PlaybackManager.state.value.durationMs)}</TrackDuration>
                <TrackMetaData>NOT_IMPLEMENTED</TrackMetaData>
                <TrackURI>${xmlEscape(PlaybackManager.state.value.uri.orEmpty())}</TrackURI>
                <RelTime>${formatDlnaDuration(PlaybackManager.state.value.positionMs)}</RelTime>
                <AbsTime>${formatDlnaDuration(PlaybackManager.state.value.positionMs)}</AbsTime>
                <RelCount>2147483647</RelCount>
                <AbsCount>2147483647</AbsCount>
                """.trimIndent(),
            )
            "GetMediaInfo" -> dlnaActionResponse(
                "u:GetMediaInfoResponse",
                """
                <NrTracks>1</NrTracks>
                <MediaDuration>${formatDlnaDuration(PlaybackManager.state.value.durationMs)}</MediaDuration>
                <CurrentURI>${xmlEscape(PlaybackManager.state.value.uri.orEmpty())}</CurrentURI>
                <CurrentURIMetaData>NOT_IMPLEMENTED</CurrentURIMetaData>
                <NextURI></NextURI>
                <NextURIMetaData></NextURIMetaData>
                <PlayMedium>NETWORK</PlayMedium>
                <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
                """.trimIndent(),
            )
            "GetCurrentTransportActions" -> dlnaActionResponse(
                "u:GetCurrentTransportActionsResponse",
                "<Actions>Play,Pause,Stop,Seek</Actions>",
            )
            else -> dlnaActionResponse("u:${action}Response")
        }
    }

    private fun handleRenderingControl(session: IHTTPSession): String {
        val action = session.headers["soapaction"]
            ?.substringAfter("#")
            ?.substringBefore("\"")
            ?.trim()
            .orEmpty()
        val body = readBody(session).toString(StandardCharsets.UTF_8)

        return when (action) {
            "SetVolume" -> {
                val volume = extractXmlTag(body, "DesiredVolume")?.toIntOrNull() ?: 100
                PlaybackManager.setVolume(volume)
                dlnaActionResponse("u:SetVolumeResponse")
            }
            "GetVolume" -> dlnaActionResponse(
                "u:GetVolumeResponse",
                "<CurrentVolume>${PlaybackManager.state.value.volumePercent}</CurrentVolume>",
            )
            "SetMute" -> {
                val desiredMute = extractXmlTag(body, "DesiredMute") == "1"
                PlaybackManager.setMuted(desiredMute)
                dlnaActionResponse("u:SetMuteResponse")
            }
            "GetMute" -> dlnaActionResponse(
                "u:GetMuteResponse",
                "<CurrentMute>${if (PlaybackManager.state.value.muted) 1 else 0}</CurrentMute>",
            )
            else -> dlnaActionResponse("u:${action}Response")
        }
    }

    private fun handleConnectionManager(session: IHTTPSession): String {
        val action = session.headers["soapaction"]
            ?.substringAfter("#")
            ?.substringBefore("\"")
            ?.trim()
            .orEmpty()

        return when (action) {
            "GetProtocolInfo" -> dlnaActionResponse(
                "u:GetProtocolInfoResponse",
                """
                <Source></Source>
                <Sink>${dlnaSinkProtocolInfo()}</Sink>
                """.trimIndent(),
            )
            "GetCurrentConnectionIDs" -> dlnaActionResponse(
                "u:GetCurrentConnectionIDsResponse",
                "<ConnectionIDs>0</ConnectionIDs>",
            )
            "GetCurrentConnectionInfo" -> dlnaActionResponse(
                "u:GetCurrentConnectionInfoResponse",
                """
                <RcsID>0</RcsID>
                <AVTransportID>0</AVTransportID>
                <ProtocolInfo></ProtocolInfo>
                <PeerConnectionManager></PeerConnectionManager>
                <PeerConnectionID>-1</PeerConnectionID>
                <Direction>Input</Direction>
                <Status>OK</Status>
                """.trimIndent(),
            )
            else -> dlnaActionResponse("u:${action}Response")
        }
    }

    private fun buildAirPlayServerInfo(): String = plist(
        """
        <key>deviceid</key><string>${PlaybackManager.airPlayDeviceId}</string>
        <key>features</key><integer>8755</integer>
        <key>model</key><string>AppleTV3,2</string>
        <key>protovers</key><string>1.0</string>
        <key>srcvers</key><string>220.68</string>
        """.trimIndent(),
    )

    private fun buildPlaybackInfo(): String {
        val durationSeconds = PlaybackManager.state.value.durationMs / 1000.0
        val positionSeconds = PlaybackManager.state.value.positionMs / 1000.0
        val rate = if (PlaybackManager.state.value.isPlaying) 1 else 0
        return plist(
            """
            <key>duration</key><real>$durationSeconds</real>
            <key>loadedTimeRanges</key>
            <array>
              <dict>
                <key>duration</key><real>$durationSeconds</real>
                <key>start</key><real>0</real>
              </dict>
            </array>
            <key>playbackBufferEmpty</key><false/>
            <key>playbackBufferFull</key><true/>
            <key>playbackLikelyToKeepUp</key><true/>
            <key>position</key><real>$positionSeconds</real>
            <key>rate</key><real>$rate</real>
            <key>readyToPlay</key><true/>
            <key>seekableTimeRanges</key>
            <array>
              <dict>
                <key>duration</key><real>$durationSeconds</real>
                <key>start</key><real>0</real>
              </dict>
            </array>
            """.trimIndent(),
        )
    }

    private fun buildDeviceDescription(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>http://$localIp:${ReceiverRuntime.SERVER_PORT}/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>${PlaybackManager.state.value.deviceName}</friendlyName>
            <manufacturer>HANG's TV</manufacturer>
            <manufacturerURL>https://hangstv.local/</manufacturerURL>
            <modelDescription>HANG's TV Receiver</modelDescription>
            <modelName>HANG's TV Receiver</modelName>
            <modelNumber>0.1.0</modelNumber>
            <presentationURL>http://$localIp:${ReceiverRuntime.SERVER_PORT}/dlna/device.xml</presentationURL>
            <UDN>uuid:${deviceUuid()}</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/dlna/control/avtransport</controlURL>
                <eventSubURL>/dlna/event/avtransport</eventSubURL>
                <SCPDURL>/dlna/service/avtransport.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/dlna/control/rendering</controlURL>
                <eventSubURL>/dlna/event/rendering</eventSubURL>
                <SCPDURL>/dlna/service/rendering.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <controlURL>/dlna/control/connection</controlURL>
                <eventSubURL>/dlna/event/connection</eventSubURL>
                <SCPDURL>/dlna/service/connection.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun avTransportScpd(): String = serviceScpd(
        "AVTransport",
        """
        <action><name>SetAVTransportURI</name></action>
        <action><name>Play</name></action>
        <action><name>Pause</name></action>
        <action><name>Stop</name></action>
        <action><name>Seek</name></action>
        <action><name>GetTransportInfo</name></action>
        <action><name>GetPositionInfo</name></action>
        <action><name>GetMediaInfo</name></action>
        <action><name>GetCurrentTransportActions</name></action>
        """.trimIndent(),
    )

    private fun renderingControlScpd(): String = serviceScpd(
        "RenderingControl",
        """
        <action><name>SetVolume</name></action>
        <action><name>GetVolume</name></action>
        <action><name>SetMute</name></action>
        <action><name>GetMute</name></action>
        """.trimIndent(),
    )

    private fun connectionManagerScpd(): String = serviceScpd(
        "ConnectionManager",
        """
        <action><name>GetProtocolInfo</name></action>
        <action><name>GetCurrentConnectionIDs</name></action>
        <action><name>GetCurrentConnectionInfo</name></action>
        """.trimIndent(),
    )

    private fun serviceScpd(name: String, actions: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>$actions</actionList>
          <serviceStateTable>${serviceStateTable(name)}</serviceStateTable>
        </scpd>
    """.trimIndent()

    private fun dlnaActionResponse(actionName: String, innerXml: String = ""): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <$actionName xmlns:u="urn:schemas-upnp-org:service:${actionName.substringAfter(':').substringBefore("Response")}:1">
              $innerXml
            </$actionName>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun plist(innerXml: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>$innerXml</dict></plist>
    """.trimIndent()

    private fun emptyPlist(): String = plist("")

    private fun textParametersResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/parameters", body)

    private fun xmlResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", body)

    private fun soapResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", body).apply {
            addHeader("EXT", "")
        }

    private inline fun emptyOk(block: () -> Unit): Response {
        block()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    }

    private fun readBody(session: IHTTPSession): ByteArray {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val payload = files["postData"] ?: return ByteArray(0)
        val candidateFile = File(payload)
        return if (candidateFile.exists()) {
            candidateFile.readBytes()
        } else {
            payload.toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun buildRequestFromPlist(body: ByteArray): ReceiverPlaybackRequest {
        val root = PropertyListParser.parse(body)
        val xml = root.toXMLPropertyList()
        val uri = extractXmlTag(xml, "Content-Location").orEmpty()
        val startPosition = extractXmlTag(xml, "Start-Position")?.toDoubleOrNull() ?: 0.0
        return ReceiverPlaybackRequest(
            uri = uri,
            startPositionMs = (startPosition * 1000).toLong(),
        )
    }

    private fun buildRequestFromParameters(body: String): ReceiverPlaybackRequest {
        val params = body.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else {
                    line.substring(0, index).trim() to line.substring(index + 1).trim()
                }
            }
            .toMap()

        return ReceiverPlaybackRequest(
            uri = params["Content-Location"].orEmpty(),
            title = params["Title"],
            mimeType = params["Content-Type"],
            startPositionMs = ((params["Start-Position"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
        )
    }

    private fun transportState(): String = when {
        PlaybackManager.state.value.mediaKind == ReceiverMediaKind.Idle -> "STOPPED"
        PlaybackManager.state.value.isPlaying -> "PLAYING"
        else -> "PAUSED_PLAYBACK"
    }

    private fun parseDlnaTitle(metadata: String?): String? = metadata
        ?.let { extractXmlTag(it, "dc:title") ?: extractXmlTag(it, "title") }

    private fun parseDlnaMimeType(metadata: String?): String? {
        val protocolInfo = metadata?.let { extractXmlTag(it, "res protocolInfo") }
        return protocolInfo?.split(":")?.getOrNull(2)
    }

    private fun parseDlnaDurationMs(value: String): Long {
        val parts = value.split(":")
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val seconds = parts[2].toDoubleOrNull() ?: 0.0
        return ((hours * 3600) + (minutes * 60) + seconds).times(1000).toLong()
    }

    private fun formatDlnaDuration(valueMs: Long): String {
        val totalSeconds = (valueMs / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    private fun htmlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val directTag = Regex("<$tagName>(.*?)</$tagName>", RegexOption.IGNORE_CASE)
        directTag.find(xml)?.let { return it.groupValues[1] }

        val attrTag = Regex("<$tagName[^>]*>(.*?)</${tagName.substringBefore(' ')}>", RegexOption.IGNORE_CASE)
        attrTag.find(xml)?.let { return it.groupValues[1] }

        return null
    }

    private fun deviceUuid(): String = PlaybackManager.dlnaDeviceUuid

    private fun dlnaSinkProtocolInfo(): String = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegurl:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/x-m4a:*",
        "http-get:*:audio/aac:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
        "http-get:*:image/gif:*",
    ).joinToString(",")

    private fun serviceStateTable(name: String): String = when (name) {
        "AVTransport" -> """
            <stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType><defaultValue>STOPPED</defaultValue></stateVariable>
            <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType><defaultValue>OK</defaultValue></stateVariable>
            <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType><defaultValue>NOT_IMPLEMENTED</defaultValue></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTransportActions</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType><defaultValue>00:00:00</defaultValue></stateVariable>
            <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType><defaultValue>00:00:00</defaultValue></stateVariable>
        """.trimIndent()
        "RenderingControl" -> """
            <stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><defaultValue>100</defaultValue></stateVariable>
            <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType><defaultValue>0</defaultValue></stateVariable>
        """.trimIndent()
        "ConnectionManager" -> """
            <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType><defaultValue>${xmlEscape(dlnaSinkProtocolInfo())}</defaultValue></stateVariable>
            <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType><defaultValue>0</defaultValue></stateVariable>
        """.trimIndent()
        else -> ""
    }
}
