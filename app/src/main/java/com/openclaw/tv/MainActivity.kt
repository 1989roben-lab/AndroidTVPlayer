package com.openclaw.tv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.openclaw.core.protocol.ProtocolCapability
import com.openclaw.core.protocol.ProtocolStage
import com.openclaw.core.protocol.builtInProtocolPlan
import com.openclaw.tv.receiver.PlaybackManager
import com.openclaw.tv.receiver.ReceiverMediaKind
import com.openclaw.tv.receiver.ReceiverRuntime
import com.openclaw.tv.receiver.ReceiverService
import com.openclaw.tv.receiver.ReceiverState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("OpenClaw", "MainActivity.onCreate")
        PlaybackManager.ensureInitialized(applicationContext)
        if (!ReceiverRuntime.isStarted()) {
            ReceiverService.start(applicationContext)
        }
        setContent {
            MaterialTheme {
                Surface(color = Color.Black) {
                    val state by PlaybackManager.state.collectAsState()
                    DashboardScreen(
                        protocols = builtInProtocolPlan(),
                        state = state,
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val state = PlaybackManager.currentStateSnapshot()
                    if (state.mediaKind != ReceiverMediaKind.Idle) {
                        PlaybackManager.stop()
                    }
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    PlaybackManager.seekBy(-10_000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    PlaybackManager.seekBy(10_000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_SPACE -> {
                    val state = PlaybackManager.currentStateSnapshot()
                    if (state.mediaKind != ReceiverMediaKind.Image && state.mediaKind != ReceiverMediaKind.Idle) {
                        if (state.isPlaying) {
                            PlaybackManager.pause()
                        } else {
                            PlaybackManager.resume()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            PlaybackManager.stop()
        }
    }
}

@Composable
private fun DashboardScreen(
    protocols: List<ProtocolCapability>,
    state: ReceiverState,
) {
    if (state.mediaKind != ReceiverMediaKind.Idle) {
        FullscreenPlaybackScreen(state = state)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF09111F), Color(0xFF132A3E)),
                ),
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "OpenClaw TV Receiver",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "MVP path: AirPlay + DLNA first, TV player UI in-app, Cast optional, Miracast deferred.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFD6E4F0),
        )

        ReceiverStatusCard(state = state)
        ActivePlaybackCard(state = state)

        protocols.forEach { protocol ->
            ProtocolCard(protocol = protocol)
        }
    }
}

@Composable
private fun ReceiverStatusCard(state: ReceiverState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Receiver status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (state.airPlayReady) "AirPlay ready" else "AirPlay offline") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (state.dlnaReady) "DLNA ready" else "DLNA offline") },
                )
            }
            Text(text = "Device name: ${state.deviceName}")
            Text(text = "Address: ${state.localAddress.ifBlank { "Waiting for network" }}:${state.serverPort}")
            Text(text = state.serviceMessage)
            Text(
                text = state.receiverHint,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF52606D),
            )
            state.lastError?.let {
                Text(
                    text = "Last error: $it",
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FullscreenPlaybackScreen(state: ReceiverState) {
    val progress = when {
        state.durationMs > 0L -> (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        state.mediaKind == ReceiverMediaKind.Image -> 1f
        else -> 0f
    }
    val bufferedProgress = when {
        state.durationMs > 0L -> (state.bufferedPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (state.mediaKind) {
            ReceiverMediaKind.Video, ReceiverMediaKind.Audio -> VideoSurface(Modifier.fillMaxSize())
            ReceiverMediaKind.Image -> state.imageBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = state.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            ReceiverMediaKind.Idle -> Unit
        }

        state.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            )
        }

        if (state.mediaKind != ReceiverMediaKind.Image && state.controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${formatPlaybackTime(state.positionMs)} / ${formatPlaybackTime(state.durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color(0x33000000), RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufferedProgress)
                            .background(Color(0xFF3A3A3A), RoundedCornerShape(999.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color(0xFFFFB300), RoundedCornerShape(999.dp)),
                    )
                }
            }
        }

        state.seekPreviewPositionMs?.let { seekPreviewPositionMs ->
            Text(
                text = "跳转到 ${formatPlaybackTime(seekPreviewPositionMs)}",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x9A000000), RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun ActivePlaybackCard(state: ReceiverState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Now playing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.activeProtocol?.label ?: "Waiting for media")
            Text(text = state.title ?: "No active media")
            when (state.mediaKind) {
                ReceiverMediaKind.Video, ReceiverMediaKind.Audio -> VideoSurface(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                )
                ReceiverMediaKind.Image -> state.imageBitmap?.let { bitmap ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = state.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                ReceiverMediaKind.Idle -> Text(
                    text = "Launch the TV app and send a direct media URL from an AirPlay or DLNA sender.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.mediaKind != ReceiverMediaKind.Idle) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "URI: ${state.uri ?: "in-memory photo"}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "Position: ${state.positionMs / 1000}s / ${state.durationMs / 1000}s",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VideoSurface(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = PlaybackManager.player()
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
        update = { it.player = PlaybackManager.player() },
    )
}

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ProtocolCard(protocol: ProtocolCapability) {
    val stageColor = when (protocol.stage) {
        ProtocolStage.Now -> Color(0xFF7CE38B)
        ProtocolStage.Next -> Color(0xFFFFC857)
        ProtocolStage.Later -> Color(0xFFFF8A65)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = protocol.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = protocol.stage.label,
                    color = stageColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(text = protocol.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Source: ${protocol.sourceRepo}",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF52606D),
            )
        }
    }
}
