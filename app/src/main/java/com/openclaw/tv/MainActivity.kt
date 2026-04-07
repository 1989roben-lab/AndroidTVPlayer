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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
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
                    DashboardScreen(state = state)
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
    state: ReceiverState,
) {
    if (state.mediaKind != ReceiverMediaKind.Idle) {
        FullscreenPlaybackScreen(state = state)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF05070D), Color(0xFF0B1020), Color(0xFF05070D)),
                ),
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 120.dp)
                .size(440.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x40A6B7FF), Color.Transparent),
                    ),
                    shape = RoundedCornerShape(220.dp),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 60.dp, bottom = 80.dp)
                .size(360.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x1FE6C087), Color.Transparent),
                    ),
                    shape = RoundedCornerShape(180.dp),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 68.dp)
                .fillMaxWidth(0.64f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "HANG's TV",
                color = Color(0xFFF5F7FB),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "A quiet screen for the living room. Ready for AirPlay and DLNA the moment you send something.",
                color = Color(0xB8F5F7FB),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = state.receiverHint,
                color = Color(0x80F5F7FB),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    label = if (state.airPlayReady) "AirPlay Ready" else "AirPlay Offline",
                    accent = if (state.airPlayReady) Color(0xFF7FE6C9) else Color(0x55FFFFFF),
                )
                StatusPill(
                    label = if (state.dlnaReady) "DLNA Ready" else "DLNA Offline",
                    accent = if (state.dlnaReady) Color(0xFFF5C46A) else Color(0x55FFFFFF),
                )
            }
            Text(
                text = state.localAddress.ifBlank { "Waiting for network" },
                color = Color(0x66F5F7FB),
                style = MaterialTheme.typography.bodyLarge,
            )
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
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(horizontal = 38.dp, vertical = 28.dp),
            )
        }

        if (state.mediaKind != ReceiverMediaKind.Image && state.controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.84f)
                    .safeDrawingPadding()
                    .background(Color(0x2A21334B), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatPlaybackTime(state.positionMs),
                        color = Color(0xE8F4FBFF),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = formatPlaybackTime(state.durationMs),
                        color = Color(0xB7F4FBFF),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0x3C9CBED0), Color(0x6E2A394A), Color(0x8F0E1721)),
                                ),
                                shape = RoundedCornerShape(999.dp),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufferedProgress)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0x4A77D4F0), Color(0x663A677B)),
                                ),
                                shape = RoundedCornerShape(999.dp),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF5EE5FF),
                                        Color(0xFF1EB7E9),
                                        Color(0xFF0E9ED5),
                                    ),
                                ),
                                shape = RoundedCornerShape(999.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0x9AFFFFFF), Color(0x18FFFFFF)),
                                    ),
                                    RoundedCornerShape(999.dp),
                                ),
                        )
                    }
                }
            }
        }

        state.seekPreviewPositionMs?.let { seekPreviewPositionMs ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x7A10131A), RoundedCornerShape(26.dp))
                    .padding(horizontal = 32.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Seek",
                    color = Color(0x99FFFFFF),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                )
                Text(
                    text = formatPlaybackTime(seekPreviewPositionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .background(Color(0x18FFFFFF), RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
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
