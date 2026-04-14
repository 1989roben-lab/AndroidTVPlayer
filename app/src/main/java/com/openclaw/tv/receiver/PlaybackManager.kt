package com.openclaw.tv.receiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.openclaw.tv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object PlaybackManager {
    private const val TAG = "OpenClawPlayback"
    private const val SEEK_COMMIT_DELAY_MS = 220L
    private const val SEEK_PREVIEW_DISMISS_DELAY_MS = 900L
    private const val CONTROLS_DISMISS_DELAY_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialized = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var appContext: Context
    private lateinit var exoPlayer: ExoPlayer

    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private var pendingDlnaRequest: ReceiverPlaybackRequest? = null
    private var pendingNextDlnaRequest: ReceiverPlaybackRequest? = null
    private var pendingSeekPositionMs: Long? = null
    private val positionTicker = object : Runnable {
        override fun run() {
            if (::exoPlayer.isInitialized) {
                val displayPosition = pendingSeekPositionMs ?: exoPlayer.currentPosition.coerceAtLeast(0L)
                _state.value = _state.value.copy(
                    positionMs = displayPosition,
                    durationMs = exoPlayer.duration.takeIf { it > 0L } ?: 0L,
                    bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    isPlaying = exoPlayer.isPlaying,
                )
                handler.postDelayed(this, 1000L)
            }
        }
    }
    private val commitSeekRunnable = Runnable {
        val target = pendingSeekPositionMs ?: return@Runnable
        if (::exoPlayer.isInitialized) {
            Log.d(TAG, "commitSeek target=$target")
            exoPlayer.seekTo(target)
        }
        pendingSeekPositionMs = null
        handler.removeCallbacks(clearSeekPreviewRunnable)
        handler.postDelayed(clearSeekPreviewRunnable, SEEK_PREVIEW_DISMISS_DELAY_MS)
    }
    private val clearSeekPreviewRunnable = Runnable {
        _state.value = _state.value.copy(seekPreviewPositionMs = null)
    }
    private val hideControlsRunnable = Runnable {
        _state.value = _state.value.copy(controlsVisible = false)
    }

    val airPlayDeviceId: String
        get() = ReceiverRuntime.airPlayDeviceId(appContext)

    val dlnaDeviceUuid: String
        get() = ReceiverRuntime.receiverUuid(appContext)

    fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("OpenClawReceiver/0.1")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,
                300_000,
                1_500,
                3_000,
            )
            .setBackBuffer(120_000, true)
            .build()

        exoPlayer = ExoPlayer.Builder(appContext)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged=$isPlaying")
                        _state.value = _state.value.copy(isPlaying = isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged=$playbackState")
                        val message = when (playbackState) {
                            Player.STATE_BUFFERING -> "Buffering media"
                            Player.STATE_READY -> "Ready for playback"
                            Player.STATE_ENDED -> {
                                playNextDlnaRequestIfAvailable()
                                "Playback finished"
                            }
                            else -> _state.value.serviceMessage
                        }
                        _state.value = _state.value.copy(serviceMessage = message)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "playerError=${error.errorCodeName}", error)
                    }
                })
            }

        handler.post(positionTicker)
    }

    fun player(): ExoPlayer = exoPlayer

    fun updateServiceState(
        airPlayReady: Boolean = _state.value.airPlayReady,
        dlnaReady: Boolean = _state.value.dlnaReady,
        localAddress: String = _state.value.localAddress,
        serviceMessage: String = _state.value.serviceMessage,
        lastError: String? = _state.value.lastError,
    ) {
        _state.value = _state.value.copy(
            airPlayReady = airPlayReady,
            dlnaReady = dlnaReady,
            localAddress = localAddress,
            serviceMessage = serviceMessage,
            lastError = lastError,
        )
    }

    fun prepareDlnaRequest(request: ReceiverPlaybackRequest) {
        pendingDlnaRequest = request
        _state.value = _state.value.copy(
            activeProtocol = ReceiverProtocol.Dlna,
            serviceMessage = "DLNA media prepared",
            title = request.title,
            uri = request.uri,
            mimeType = request.mimeType,
            lastError = null,
        )
    }

    fun prepareDlnaRequest(uri: String, title: String?, mimeType: String?) {
        prepareDlnaRequest(
            ReceiverPlaybackRequest(
                uri = uri,
                title = title,
                mimeType = mimeType,
            ),
        )
    }

    fun prepareNextDlnaRequest(request: ReceiverPlaybackRequest) {
        pendingNextDlnaRequest = request
        Log.d(TAG, "prepared next DLNA request uri=${request.uri}")
    }

    fun prepareNextDlnaRequest(uri: String, title: String?, mimeType: String?) {
        prepareNextDlnaRequest(
            ReceiverPlaybackRequest(
                uri = uri,
                title = title,
                mimeType = mimeType,
            ),
        )
    }

    fun playPreparedDlnaRequest() {
        pendingDlnaRequest?.let { play(it, ReceiverProtocol.Dlna) }
    }

    fun hasPendingDlnaRequest(): Boolean = pendingDlnaRequest != null

    fun hasPendingNextDlnaRequest(): Boolean = pendingNextDlnaRequest != null

    fun pendingNextDlnaRequestSnapshot(): ReceiverPlaybackRequest? = pendingNextDlnaRequest

    fun playNextDlnaRequestIfAvailable() {
        val request = pendingNextDlnaRequest ?: return
        pendingNextDlnaRequest = null
        pendingDlnaRequest = request
        handler.post {
            Log.d(TAG, "playNextDlnaRequestIfAvailable uri=${request.uri}")
            play(request, ReceiverProtocol.Dlna)
        }
    }

    fun play(request: ReceiverPlaybackRequest, protocol: ReceiverProtocol) {
        ensureInitialized(appContext)
        pendingDlnaRequest = if (protocol == ReceiverProtocol.Dlna) request else pendingDlnaRequest

        scope.launch {
            Log.d(TAG, "play request protocol=${protocol.label} uri=${request.uri} mime=${request.mimeType}")
            when {
                request.mimeType?.startsWith("image/") == true || request.uri.isLikelyImageUri() -> {
                    displayImage(request, protocol)
                }
                else -> {
                    playStream(request, protocol)
                }
            }
        }
    }

    fun displayPhotoBytes(bytes: ByteArray, protocol: ReceiverProtocol, title: String = "Photo") {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeStream(ByteArrayInputStream(bytes))
            }
            exoPlayer.stop()
            bringPlayerToFront()
            _state.value = _state.value.copy(
                activeProtocol = protocol,
                mediaKind = ReceiverMediaKind.Image,
                title = title,
                imageBitmap = bitmap,
                uri = null,
                mimeType = "image/jpeg",
                isPlaying = true,
                serviceMessage = "${protocol.label} image received",
                lastError = null,
            )
        }
    }

    fun pause() {
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    fun resume() {
        if (_state.value.mediaKind == ReceiverMediaKind.Image) return
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.play()
                _state.value = _state.value.copy(isPlaying = true)
            }
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(commitSeekRunnable)
            handler.removeCallbacks(clearSeekPreviewRunnable)
            handler.removeCallbacks(hideControlsRunnable)
            pendingSeekPositionMs = null
            pendingNextDlnaRequest = null
            if (::exoPlayer.isInitialized) {
                exoPlayer.stop()
            }
            _state.value = _state.value.copy(
                mediaKind = ReceiverMediaKind.Idle,
                title = null,
                uri = null,
                mimeType = null,
                imageBitmap = null,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                seekPreviewPositionMs = null,
                controlsVisible = false,
                serviceMessage = "Waiting for AirPlay or DLNA media",
                lastError = null,
            )
        }
    }

    fun seekTo(positionMs: Long) {
        handler.post {
            if (!::exoPlayer.isInitialized || _state.value.mediaKind == ReceiverMediaKind.Image) return@post
            val durationMs = exoPlayer.duration.takeIf { it > 0L }
            val target = positionMs.coerceAtLeast(0L).let { desired ->
                durationMs?.let { desired.coerceAtMost(it) } ?: desired
            }
            pendingSeekPositionMs = target
            _state.value = _state.value.copy(
                positionMs = target,
                seekPreviewPositionMs = target,
                controlsVisible = true,
            )
            handler.removeCallbacks(commitSeekRunnable)
            handler.removeCallbacks(clearSeekPreviewRunnable)
            handler.removeCallbacks(hideControlsRunnable)
            handler.postDelayed(commitSeekRunnable, SEEK_COMMIT_DELAY_MS)
            handler.postDelayed(hideControlsRunnable, CONTROLS_DISMISS_DELAY_MS)
        }
    }

    fun seekBy(deltaMs: Long) {
        handler.post {
            if (!::exoPlayer.isInitialized || _state.value.mediaKind == ReceiverMediaKind.Image) return@post
            val durationMs = exoPlayer.duration.takeIf { it > 0L }
            val basePosition = pendingSeekPositionMs ?: exoPlayer.currentPosition
            val target = (basePosition + deltaMs).coerceAtLeast(0L).let { desired ->
                durationMs?.let { desired.coerceAtMost(it) } ?: desired
            }
            pendingSeekPositionMs = target
            _state.value = _state.value.copy(
                positionMs = target,
                seekPreviewPositionMs = target,
                controlsVisible = true,
            )
            handler.removeCallbacks(commitSeekRunnable)
            handler.removeCallbacks(clearSeekPreviewRunnable)
            handler.removeCallbacks(hideControlsRunnable)
            handler.postDelayed(commitSeekRunnable, SEEK_COMMIT_DELAY_MS)
            handler.postDelayed(hideControlsRunnable, CONTROLS_DISMISS_DELAY_MS)
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.volume = clamped / 100f
            }
            _state.value = _state.value.copy(volumePercent = clamped, muted = clamped == 0)
        }
    }

    fun setMuted(muted: Boolean) {
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.volume = if (muted) 0f else _state.value.volumePercent / 100f
            }
            _state.value = _state.value.copy(muted = muted)
        }
    }

    fun currentStateSnapshot(): ReceiverState = _state.value

    private suspend fun displayImage(request: ReceiverPlaybackRequest, protocol: ReceiverProtocol) {
        val bitmap = withContext(Dispatchers.IO) {
            URL(request.uri).openStream().use(BitmapFactory::decodeStream)
        }
        exoPlayer.stop()
        bringPlayerToFront()
        _state.value = _state.value.copy(
            activeProtocol = protocol,
            mediaKind = ReceiverMediaKind.Image,
            title = request.title ?: Uri.parse(request.uri).lastPathSegment,
            uri = request.uri,
            mimeType = request.mimeType ?: "image/*",
            imageBitmap = bitmap,
            isPlaying = true,
            serviceMessage = "${protocol.label} image loaded",
            lastError = null,
        )
    }

    private fun playStream(request: ReceiverPlaybackRequest, protocol: ReceiverProtocol) {
        Log.d(TAG, "playStream uri=${request.uri}")
        val kind = if ((request.mimeType ?: request.uri.guessMimeType()).startsWith("audio/")) {
            ReceiverMediaKind.Audio
        } else {
            ReceiverMediaKind.Video
        }

        val mediaItem = MediaItem.Builder()
            .setUri(request.uri)
            .setMimeType(request.mimeType ?: request.uri.guessMimeType())
            .build()

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (request.startPositionMs > 0L) {
            exoPlayer.seekTo(request.startPositionMs)
        }
        exoPlayer.playWhenReady = true
        bringPlayerToFront()

        _state.value = _state.value.copy(
            activeProtocol = protocol,
            mediaKind = kind,
            title = request.title ?: Uri.parse(request.uri).lastPathSegment,
            uri = request.uri,
            mimeType = request.mimeType ?: request.uri.guessMimeType(),
            imageBitmap = null,
            isPlaying = true,
            serviceMessage = "${protocol.label} playback started",
            lastError = null,
        )
    }

    private fun bringPlayerToFront() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }
}

private fun String.isLikelyImageUri(): Boolean = endsWith(".jpg", true) ||
    endsWith(".jpeg", true) ||
    endsWith(".png", true) ||
    endsWith(".webp", true)

private fun String.guessMimeType(): String = when {
    endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
    endsWith(".mp4", true) -> MimeTypes.VIDEO_MP4
    endsWith(".mp3", true) -> MimeTypes.AUDIO_MPEG
    endsWith(".m4a", true) -> MimeTypes.AUDIO_MP4
    endsWith(".aac", true) -> MimeTypes.AUDIO_AAC
    endsWith(".jpg", true) || endsWith(".jpeg", true) -> "image/jpeg"
    endsWith(".png", true) -> "image/png"
    else -> MimeTypes.APPLICATION_MP4
}
