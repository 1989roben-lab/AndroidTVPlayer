package com.openclaw.feature.discovery

import com.openclaw.core.protocol.ProtocolCapability
import com.openclaw.core.protocol.ProtocolStage

object DiscoveryRoadmap {
    val milestones: List<ProtocolCapability> = listOf(
        ProtocolCapability(
            name = "mDNS / Bonjour",
            summary = "Advertise AirPlay receiver presence and basic device identity on LAN.",
            sourceRepo = "Implemented around AirPlay integration",
            stage = ProtocolStage.Now,
        ),
        ProtocolCapability(
            name = "SSDP / UPnP Discovery",
            summary = "Expose TV receiver to DLNA senders and keep service announcements healthy.",
            sourceRepo = "UPnPCast integration",
            stage = ProtocolStage.Now,
        ),
    )
}
