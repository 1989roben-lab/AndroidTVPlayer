package com.openclaw.tv.receiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.openclaw.tv.MainActivity
import com.openclaw.tv.receiver.dlna.OpenClawAVTransportService
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
    private var pendingSeekAddsTimelineOffset: Boolean = false
    private var activePlayback: ActivePlayback? = null
    private var awaitingAutoNextUntilMs: Long = 0L
    private val retriedPlaybackUris = mutableSetOf<String>()
    private val positionTicker = object : Runnable {
        override fun run() {
            if (::exoPlayer.isInitialized) {
                val offsetMs = activePlayback?.timelineOffsetMs ?: 0L
                val displayPosition = pendingSeekPositionMs ?: (exoPlayer.currentPosition - offsetMs).coerceAtLeast(0L)
                val displayDuration = exoPlayer.duration
                    .takeIf { it > 0L }
                    ?.let { (it - offsetMs).coerceAtLeast(0L) }
                    ?: 0L
                val displayBufferedPosition = (exoPlayer.bufferedPosition - offsetMs).coerceAtLeast(0L)
                _state.value = _state.value.copy(
                    positionMs = displayPosition,
                    durationMs = displayDuration,
                    bufferedPositionMs = displayBufferedPosition,
                    isPlaying = exoPlayer.isPlaying,
                )
                notifyDlnaPositionChanged()
                handler.postDelayed(this, 1000L)
            }
        }
    }
    private val commitSeekRunnable = Runnable {
        val target = pendingSeekPositionMs ?: return@Runnable
        if (::exoPlayer.isInitialized) {
            val actualTarget = target + if (pendingSeekAddsTimelineOffset) activePlayback?.timelineOffsetMs ?: 0L else 0L
            Log.d(TAG, "commitSeek target=$target actualTarget=$actualTarget addsTimelineOffset=$pendingSeekAddsTimelineOffset")
            exoPlayer.seekTo(actualTarget)
        }
        pendingSeekPositionMs = null
        pendingSeekAddsTimelineOffset = false
        notifyDlnaStateChanged()
        handler.removeCallbacks(clearSeekPreviewRunnable)
        handler.postDelayed(clearSeekPreviewRunnable, SEEK_PREVIEW_DISMISS_DELAY_MS)
    }
    private val clearSeekPreviewRunnable = Runnable {
        _state.value = _state.value.copy(seekPreviewPositionMs = null)
    }
    private val closeAutoNextWindowRunnable = Runnable {
        awaitingAutoNextUntilMs = 0L
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
                        notifyDlnaStateChanged()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged=$playbackState")
                        val message = when (playbackState) {
                            Player.STATE_BUFFERING -> "Buffering media"
                            Player.STATE_READY -> "Ready for playback"
                            Player.STATE_ENDED -> {
                                if (playNextDlnaRequestIfAvailable()) {
                                    "Playing next media"
                                } else {
                                    openAutoNextWindow()
                                    "Playback finished; waiting for next media"
                                }
                            }
                            else -> _state.value.serviceMessage
                        }
                        _state.value = _state.value.copy(serviceMessage = message)
                        notifyDlnaStateChanged()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val playback = activePlayback
                        val retryPlayback = playback?.takeIf {
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                                it.media.inferredFrom != "tencent-preroll-reject" &&
                                !it.retried &&
                                retriedPlaybackUris.add(it.request.uri)
                        }
                        Log.e(
                            TAG,
                            "playerError=${error.errorCodeName} uri=${playback?.request?.uri} " +
                                "metadataMime=${playback?.request?.mimeType} finalMime=${playback?.media?.mimeType} retry=${retryPlayback != null}",
                            error,
                        )
                        if (retryPlayback != null) {
                            scope.launch {
                                playStream(retryPlayback.request, retryPlayback.protocol, forceSniff = true, retried = true)
                            }
                        } else {
                            _state.value = _state.value.copy(
                                isPlaying = false,
                                lastError = error.errorCodeName,
                                serviceMessage = "Playback error: ${error.errorCodeName}",
                            )
                            notifyDlnaStateChanged()
                        }
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
        val shouldAutoPlay = shouldAutoPlayPreparedDlnaRequest(request)
        pendingDlnaRequest = request
        _state.value = _state.value.copy(
            activeProtocol = ReceiverProtocol.Dlna,
            serviceMessage = if (shouldAutoPlay) "Auto-playing next DLNA media" else "DLNA media prepared",
            title = request.title,
            uri = request.uri,
            mimeType = request.mimeType,
            lastError = null,
        )
        notifyDlnaStateChanged()
        if (shouldAutoPlay) {
            awaitingAutoNextUntilMs = 0L
            handler.removeCallbacks(closeAutoNextWindowRunnable)
            handler.post {
                Log.d(TAG, "autoPlayPreparedDlnaRequest uri=${request.uri}")
                play(request, ReceiverProtocol.Dlna)
            }
        }
    }

    fun prepareDlnaRequest(uri: String, title: String?, mimeType: String?, metadata: String? = null) {
        prepareDlnaRequest(
            ReceiverPlaybackRequest(
                uri = uri,
                title = title,
                mimeType = mimeType,
                metadata = metadata,
            ),
        )
    }

    fun prepareNextDlnaRequest(request: ReceiverPlaybackRequest) {
        pendingNextDlnaRequest = request
        Log.d(TAG, "prepared next DLNA request uri=${request.uri}")
    }

    fun prepareNextDlnaRequest(uri: String, title: String?, mimeType: String?, metadata: String? = null) {
        prepareNextDlnaRequest(
            ReceiverPlaybackRequest(
                uri = uri,
                title = title,
                mimeType = mimeType,
                metadata = metadata,
            ),
        )
    }

    fun playPreparedDlnaRequest() {
        pendingDlnaRequest?.let { play(it, ReceiverProtocol.Dlna) }
    }

    fun hasPendingDlnaRequest(): Boolean = pendingDlnaRequest != null

    fun hasPendingNextDlnaRequest(): Boolean = pendingNextDlnaRequest != null

    fun pendingNextDlnaRequestSnapshot(): ReceiverPlaybackRequest? = pendingNextDlnaRequest

    fun playNextDlnaRequestIfAvailable(): Boolean {
        val request = pendingNextDlnaRequest ?: return false
        pendingNextDlnaRequest = null
        pendingDlnaRequest = request
        awaitingAutoNextUntilMs = 0L
        handler.removeCallbacks(closeAutoNextWindowRunnable)
        handler.post {
            Log.d(TAG, "playNextDlnaRequestIfAvailable uri=${request.uri}")
            play(request, ReceiverProtocol.Dlna)
        }
        return true
    }

    private fun openAutoNextWindow() {
        awaitingAutoNextUntilMs = System.currentTimeMillis() + AUTO_NEXT_WAIT_WINDOW_MS
        handler.removeCallbacks(closeAutoNextWindowRunnable)
        handler.postDelayed(closeAutoNextWindowRunnable, AUTO_NEXT_WAIT_WINDOW_MS)
        Log.d(TAG, "openAutoNextWindow durationMs=$AUTO_NEXT_WAIT_WINDOW_MS")
    }

    private fun shouldAutoPlayPreparedDlnaRequest(request: ReceiverPlaybackRequest): Boolean {
        val currentUri = activePlayback?.request?.uri ?: _state.value.uri
        val waiting = awaitingAutoNextUntilMs > System.currentTimeMillis()
        return waiting && request.uri.isNotBlank() && request.uri != currentUri
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
            notifyDlnaStateChanged()
        }
    }

    fun pause() {
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.pause()
                _state.value = _state.value.copy(isPlaying = false)
                notifyDlnaStateChanged()
            }
        }
    }

    fun resume() {
        if (_state.value.mediaKind == ReceiverMediaKind.Image) return
        handler.post {
            if (::exoPlayer.isInitialized) {
                exoPlayer.play()
                _state.value = _state.value.copy(isPlaying = true)
                notifyDlnaStateChanged()
            }
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(commitSeekRunnable)
            handler.removeCallbacks(clearSeekPreviewRunnable)
            handler.removeCallbacks(closeAutoNextWindowRunnable)
            handler.removeCallbacks(hideControlsRunnable)
            pendingSeekPositionMs = null
            pendingSeekAddsTimelineOffset = false
            pendingNextDlnaRequest = null
            awaitingAutoNextUntilMs = 0L
            activePlayback = null
            if (::exoPlayer.isInitialized) {
                exoPlayer.playWhenReady = false
                exoPlayer.pause()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
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
            notifyDlnaStateChanged()
        }
    }

    fun seekTo(positionMs: Long) {
        handler.post {
            if (!::exoPlayer.isInitialized || _state.value.mediaKind == ReceiverMediaKind.Image) return@post
            val offsetMs = activePlayback?.timelineOffsetMs ?: 0L
            val durationMs = exoPlayer.duration.takeIf { it > 0L }?.let { (it - offsetMs).coerceAtLeast(0L) }
            val target = positionMs.coerceAtLeast(0L).let { desired ->
                durationMs?.let { desired.coerceAtMost(it) } ?: desired
            }
            pendingSeekPositionMs = target
            pendingSeekAddsTimelineOffset = true
            _state.value = _state.value.copy(
                positionMs = target,
                seekPreviewPositionMs = target,
                controlsVisible = true,
            )
            notifyDlnaStateChanged()
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
            val offsetMs = activePlayback?.timelineOffsetMs ?: 0L
            val durationMs = exoPlayer.duration.takeIf { it > 0L }?.let { (it - offsetMs).coerceAtLeast(0L) }
            val basePosition = pendingSeekPositionMs ?: (exoPlayer.currentPosition - offsetMs).coerceAtLeast(0L)
            val target = (basePosition + deltaMs).coerceAtLeast(0L).let { desired ->
                durationMs?.let { desired.coerceAtMost(it) } ?: desired
            }
            pendingSeekPositionMs = target
            pendingSeekAddsTimelineOffset = true
            _state.value = _state.value.copy(
                positionMs = target,
                seekPreviewPositionMs = target,
                controlsVisible = true,
            )
            notifyDlnaStateChanged()
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
        notifyDlnaStateChanged()
    }

    private suspend fun playStream(
        request: ReceiverPlaybackRequest,
        protocol: ReceiverProtocol,
        forceSniff: Boolean = false,
        retried: Boolean = false,
    ) {
        val media = request.resolveMedia(forceSniff = forceSniff)
        val timelineAdjustment = if (!retried) {
            request.resolveTimelineAdjustment(media)
        } else {
            TimelineAdjustment()
        }
        if (timelineAdjustment.offsetMs > 0L) {
            Log.w(
                TAG,
                "skipTencentPrerollTimeline uri=${request.uri} metadataMime=${request.mimeType} " +
                    "finalMime=${media.mimeType} offsetMs=${timelineAdjustment.offsetMs} inferred=${timelineAdjustment.inferredFrom}",
            )
        }
        val timelineOffsetMs = timelineAdjustment.offsetMs
        val startPositionMs = timelineOffsetMs
        val displayStartPositionMs = 0L
        val metadataPreview = request.metadata?.take(240)?.replace(Regex("\\s+"), " ")
        val uriParamsPreview = request.uri.queryParametersForLog()
        val metadataFieldsPreview = request.metadata?.metadataFieldsForLog()
        Log.d(
            TAG,
            "playStream uri=${request.uri} metadataMime=${request.mimeType} finalMime=${media.mimeType} " +
                "inferred=${media.inferredFrom} retried=$retried timelineOffsetMs=$timelineOffsetMs " +
                "displayStartPositionMs=$displayStartPositionMs startPositionMs=$startPositionMs " +
                "uriParams=$uriParamsPreview metadataFields=$metadataFieldsPreview metadataPreview=$metadataPreview",
        )
        val kind = if ((media.mimeType ?: request.mimeType)?.startsWith("audio/") == true || request.uri.isLikelyAudioUri()) {
            ReceiverMediaKind.Audio
        } else {
            ReceiverMediaKind.Video
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(request.uri)
        media.mimeType?.let(mediaItemBuilder::setMimeType)
        val mediaItem = mediaItemBuilder.build()

        activePlayback = ActivePlayback(
            request = request,
            protocol = protocol,
            media = media,
            retried = retried,
            timelineOffsetMs = timelineOffsetMs,
        )
        exoPlayer.setMediaItem(mediaItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        bringPlayerToFront()

        _state.value = _state.value.copy(
            activeProtocol = protocol,
            mediaKind = kind,
            title = request.title ?: Uri.parse(request.uri).lastPathSegment,
            uri = request.uri,
            mimeType = media.mimeType ?: request.mimeType,
            imageBitmap = null,
            isPlaying = true,
            positionMs = displayStartPositionMs,
            serviceMessage = "${protocol.label} playback started",
            lastError = null,
        )
        notifyDlnaStateChanged()
    }

    private fun bringPlayerToFront() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }

    private fun notifyDlnaStateChanged() {
        if (_state.value.activeProtocol == ReceiverProtocol.Dlna) {
            OpenClawAVTransportService.publishStateChangeSoon()
        }
    }

    private fun notifyDlnaPositionChanged() {
        if (_state.value.activeProtocol == ReceiverProtocol.Dlna && _state.value.mediaKind != ReceiverMediaKind.Idle) {
            OpenClawAVTransportService.publishPositionChangeSoon()
        }
    }
}

private data class ActivePlayback(
    val request: ReceiverPlaybackRequest,
    val protocol: ReceiverProtocol,
    val media: ResolvedMedia,
    val retried: Boolean,
    val timelineOffsetMs: Long,
)

private data class ResolvedMedia(
    val mimeType: String?,
    val inferredFrom: String,
)

private data class TimelineAdjustment(
    val offsetMs: Long = 0L,
    val inferredFrom: String = "none",
)

private const val TENCENT_PREROLL_SKIP_MS = 15_500L
private const val AUTO_NEXT_WAIT_WINDOW_MS = 45_000L
private const val PLAYBACK_LOG_TAG = "OpenClawPlayback"

private fun String.isLikelyImageUri(): Boolean = endsWith(".jpg", true) ||
    endsWith(".jpeg", true) ||
    endsWith(".png", true) ||
    endsWith(".webp", true)

private fun ReceiverPlaybackRequest.resolveMedia(forceSniff: Boolean = false): ResolvedMedia {
    if (forceSniff) {
        return ResolvedMedia(mimeType = uri.guessMimeTypeFromUriOnly(), inferredFrom = "retry-uri-or-sniff")
    }
    uri.guessMimeTypeFromUriOnly()?.let { return ResolvedMedia(mimeType = it, inferredFrom = "uri") }
    mimeType?.takeIf { it.isSupportedMetadataMime() }?.let {
        return ResolvedMedia(mimeType = it.normalizeMetadataMime(), inferredFrom = "metadata")
    }
    return ResolvedMedia(mimeType = null, inferredFrom = "sniff")
}

private fun ReceiverPlaybackRequest.isLikelyTencentPrerollHls(media: ResolvedMedia): Boolean {
    val normalized = uri.lowercase()
    return media.mimeType == MimeTypes.APPLICATION_M3U8 &&
        normalized.contains("playproxy.video.qq.com") &&
        normalized.contains("dlnam3u8") &&
        normalized.contains("vt=2680")
}

private suspend fun ReceiverPlaybackRequest.resolveTimelineAdjustment(media: ResolvedMedia): TimelineAdjustment {
    if (!isLikelyTencentPrerollHls(media)) {
        return TimelineAdjustment()
    }
    val playlist = runCatching {
        withContext(Dispatchers.IO) {
            URL(uri).openStream().bufferedReader().use { it.readText() }
        }
    }.getOrElse { error ->
        Log.w(PLAYBACK_LOG_TAG, "hlsPrerollProbeFailed uri=$uri fallbackMs=$TENCENT_PREROLL_SKIP_MS error=${error.message}")
        return TimelineAdjustment(TENCENT_PREROLL_SKIP_MS, "tencent-fallback")
    }
    val offsetMs = playlist.detectLeadingDiscontinuityOffsetMs()
    return if (offsetMs > 0L) {
        TimelineAdjustment(offsetMs, "hls-leading-discontinuity")
    } else {
        TimelineAdjustment(TENCENT_PREROLL_SKIP_MS, "tencent-fallback-no-marker")
    }
}

private fun String.detectLeadingDiscontinuityOffsetMs(): Long {
    var pendingDurationMs: Long? = null
    var accumulatedMs = 0L
    var sawSegment = false
    lineSequence()
        .map { it.trim() }
        .forEach { line ->
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingDurationMs = line
                        .substringAfter(':')
                        .substringBefore(',')
                        .toDoubleOrNull()
                        ?.let { (it * 1000).toLong() }
                }
                line.startsWith("#EXT-X-DATERANGE", ignoreCase = true) && line.contains("DURATION=", ignoreCase = true) -> {
                    return line
                        .substringAfter("DURATION=", "")
                        .substringBefore(',')
                        .substringBefore(' ')
                        .trim('"')
                        .toDoubleOrNull()
                        ?.let { (it * 1000).toLong() + 500L }
                        ?: 0L
                }
                line.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) && sawSegment && accumulatedMs > 0L -> {
                    return accumulatedMs + 500L
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    pendingDurationMs?.let { accumulatedMs += it }
                    pendingDurationMs = null
                    sawSegment = true
                }
            }
        }
    return 0L
}

private fun String.queryParametersForLog(): String {
    val parsed = runCatching { Uri.parse(this) }.getOrNull() ?: return "{}"
    val names = parsed.queryParameterNames.sorted()
    if (names.isEmpty()) return "{}"
    return names.joinToString(prefix = "{", postfix = "}") { name ->
        val value = parsed.getQueryParameter(name).orEmpty()
        "$name=${value.take(120)}"
    }
}

private fun String.metadataFieldsForLog(): String {
    val compact = replace(Regex("\\s+"), " ")
    val tags = Regex("<([A-Za-z0-9_:.-]+)(?:\\s[^>]*)?>([^<]{0,160})</\\1>")
        .findAll(compact)
        .map { "${it.groupValues[1]}=${it.groupValues[2].trim()}" }
        .take(20)
        .toList()
    val attributes = Regex("([A-Za-z0-9_:.-]+)=\"([^\"]{0,160})\"")
        .findAll(compact)
        .map { "${it.groupValues[1]}=${it.groupValues[2].trim()}" }
        .take(30)
        .toList()
    return (tags + attributes).joinToString(prefix = "{", postfix = "}")
}

private fun String.guessMimeTypeFromUriOnly(): String? {
    val normalized = substringBefore('#').lowercase()
    val path = normalized.substringBefore('?')
    val query = normalized.substringAfter('?', missingDelimiterValue = "")
    return when {
        path.endsWith(".m3u8") || normalized.contains("dlnam3u8") || query.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".ism") || path.endsWith(".ism/manifest") || path.endsWith("/manifest") -> MimeTypes.APPLICATION_SS
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
        path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
        path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
        path.endsWith(".m4a") -> MimeTypes.AUDIO_MP4
        path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".png") -> "image/png"
        else -> null
    }
}

private fun String.isLikelyAudioUri(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac")
}

private fun String.isSupportedMetadataMime(): Boolean = startsWith("video/") ||
    startsWith("audio/") ||
    startsWith("image/") ||
    this == MimeTypes.APPLICATION_M3U8 ||
    this == MimeTypes.APPLICATION_MPD ||
    this == MimeTypes.APPLICATION_SS ||
    this == "application/vnd.apple.mpegurl" ||
    this == "audio/mpegurl" ||
    this == "audio/x-mpegurl"

private fun String.normalizeMetadataMime(): String = when (lowercase()) {
    "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
    else -> this
}
