package com.openclaw.feature.player

import com.openclaw.core.player.PlaybackRequest

data class PlayerFeatureSpec(
    val supportsRemotePause: Boolean,
    val supportsRemoteSeek: Boolean,
    val acceptedRequests: List<PlaybackRequest>,
)

fun defaultPlayerFeatureSpec(): PlayerFeatureSpec = PlayerFeatureSpec(
    supportsRemotePause = true,
    supportsRemoteSeek = true,
    acceptedRequests = emptyList(),
)
