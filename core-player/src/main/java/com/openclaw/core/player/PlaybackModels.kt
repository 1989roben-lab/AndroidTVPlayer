package com.openclaw.core.player

data class PlaybackRequest(
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

interface PlayerGateway {
    fun play(request: PlaybackRequest)
    fun stop()
}
