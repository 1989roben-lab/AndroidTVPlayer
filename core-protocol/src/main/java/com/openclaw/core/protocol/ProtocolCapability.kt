package com.openclaw.core.protocol

data class ProtocolCapability(
    val name: String,
    val summary: String,
    val sourceRepo: String,
    val stage: ProtocolStage,
)

enum class ProtocolStage(val label: String) {
    Now("Build Now"),
    Next("Evaluate Next"),
    Later("Defer"),
}

fun builtInProtocolPlan(): List<ProtocolCapability> = listOf(
    ProtocolCapability(
        name = "AirPlay Receiver",
        summary = "Primary MVP path for iPhone/iPad URL, image, and audio casting via an Android-side receiver.",
        sourceRepo = "warren-bank/Android-ExoPlayer-AirPlay-Receiver",
        stage = ProtocolStage.Now,
    ),
    ProtocolCapability(
        name = "DLNA / UPnP",
        summary = "Second protocol leg for LAN casting and device discovery, suitable for Android TV boxes and Xiaomi TV class devices.",
        sourceRepo = "yinnho/UPnPCast",
        stage = ProtocolStage.Now,
    ),
    ProtocolCapability(
        name = "Google Cast",
        summary = "Optional ecosystem add-on after core receiver flow is stable and TV UI is production-ready.",
        sourceRepo = "googlecast/CastAndroidTvReceiver",
        stage = ProtocolStage.Next,
    ),
    ProtocolCapability(
        name = "Miracast",
        summary = "High-risk path due to Wi-Fi Direct, driver, and codec compatibility issues across Android TV hardware.",
        sourceRepo = "Commercial SDKs / device-specific stacks",
        stage = ProtocolStage.Later,
    ),
)
