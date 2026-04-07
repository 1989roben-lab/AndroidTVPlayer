package com.openclaw.tv.receiver

import android.graphics.Bitmap

enum class ReceiverProtocol(val label: String) {
    AirPlay("AirPlay"),
    Dlna("DLNA / UPnP"),
    Local("Local"),
}

enum class ReceiverMediaKind {
    Idle,
    Video,
    Audio,
    Image,
}

data class ReceiverPlaybackRequest(
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
)

data class ReceiverState(
    val deviceName: String = "HANG's TV",
    val localAddress: String = "",
    val serverPort: Int = ReceiverRuntime.SERVER_PORT,
    val airPlayReady: Boolean = false,
    val dlnaReady: Boolean = false,
    val serviceMessage: String = "Starting receiver services",
    val activeProtocol: ReceiverProtocol? = null,
    val mediaKind: ReceiverMediaKind = ReceiverMediaKind.Idle,
    val title: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val imageBitmap: Bitmap? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val seekPreviewPositionMs: Long? = null,
    val controlsVisible: Boolean = false,
    val volumePercent: Int = 100,
    val muted: Boolean = false,
    val lastError: String? = null,
    val receiverHint: String = "Look for HANG's TV from your iPhone or any DLNA sender.",
)
