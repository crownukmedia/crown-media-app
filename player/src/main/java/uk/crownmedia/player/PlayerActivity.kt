package uk.crownmedia.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.media3.common.util.UnstableApi
import androidx.appcompat.app.AlertDialog
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import uk.crownmedia.core.design.StreamAvailability
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var playerTitle: TextView
    private lateinit var playbackError: View
    private lateinit var playbackLoading: View
    private lateinit var playbackErrorMessage: TextView
    private lateinit var playbackLoadingMessage: TextView
    private lateinit var playbackRetry: Button
    private lateinit var playbackBack: Button
    private lateinit var availability: StreamAvailability
    private var player: ExoPlayer? = null
    private var contentKey = ""
    private var resumeEnabled = false
    private var failureRecorded = false
    private var successRecorded = false
    private var failureStage = "created"
    private var fallbackAttempted = false
    private var currentUrl = ""
    private var isLive = false
    private var hasReachedReady = false
    private var userPaused = false
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var recoveryPolicy: PlaybackRecoveryPolicy
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val startupTimeout = Runnable {
        logFailure("startup_timeout", null)
        if (!attemptRecovery(recoverable = true, allowFallback = true)) {
            showUnavailable(getString(R.string.playback_timeout_detail))
        }
    }
    private val rebufferTimeout = Runnable {
        if (!isLive || userPaused || player?.playbackState != Player.STATE_BUFFERING) return@Runnable
        logFailure("live_rebuffer_timeout", null)
        if (!attemptRecovery(recoverable = true, allowFallback = true)) {
            showUnavailable(getString(R.string.playback_timeout_detail))
        }
    }
    private val stablePlayback = Runnable {
        if (isLive && player?.isPlaying == true) recoveryPolicy.onStablePlayback()
    }
    private val automaticRetry = Runnable {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            release()
            initialize()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        playerView.setShowSubtitleButton(false)
        configureSubtitleRendering()
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
            showPlayerSettings()
        }
        playerTitle = findViewById(R.id.player_title)
        playbackError = findViewById(R.id.playback_error)
        playbackLoading = findViewById(R.id.playback_loading)
        playbackErrorMessage = findViewById(R.id.playback_error_message)
        playbackLoadingMessage = findViewById(R.id.playback_loading_message)
        playbackRetry = findViewById(R.id.playback_retry)
        playbackBack = findViewById(R.id.playback_back)
        listOf(playbackRetry, playbackBack).forEach { button ->
            button.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.03f else 1f).scaleY(if (focused) 1.03f else 1f)
                    .translationZ(if (focused) 10f else 0f).setDuration(120).start()
            }
        }
        availability = StreamAvailability(this)
        playerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        resumeEnabled = intent.getBooleanExtra(EXTRA_RESUME, false)
        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        recoveryPolicy = PlaybackRecoveryPolicy(isLive)
        playbackLoadingMessage.setText(if (isLive) R.string.opening_channel else R.string.opening_video)
        playbackBack.setText(if (isLive) R.string.back_to_channels else R.string.back_to_content)
        contentKey = hash(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onStart() { super.onStart(); initialize() }
    override fun onStop() { release(); super.onStop() }

    private fun initialize() {
        if (player != null) return
        val url = currentUrl.takeIf(String::isNotBlank) ?: run { finish(); return }
        hasReachedReady = false
        failureStage = "initializing"
        val buffer = intent.getStringExtra(EXTRA_BUFFER) ?: "normal"
        val loadControl = when (buffer) {
            "low" -> DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 500, 1_000).build()
            "resilient" -> DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 90_000, 2_500, 5_000).build()
            else -> DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 45_000, 1_500, 3_000).build()
        }
        val httpDataSource = OkHttpDataSource.Factory(PLAYBACK_HTTP_CLIENT)
            .setUserAgent(PLAYBACK_USER_AGENT)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Accept-Encoding" to "identity"))
        val dataSource = DefaultDataSource.Factory(this, httpDataSource)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setExceedAudioConstraintsIfNecessary(true)
                .build()
        }
        trackSelector = selector
        val instance = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        instance.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        instance.setHandleAudioBecomingNoisy(true)
        instance.volume = 1f
        player = instance
        playerView.player = instance
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = buildMediaItem(url, mime, externalSubtitles())
        logPrepare(url, mime)
        instance.setMediaItem(item)
        if (resumeEnabled) {
            val saved = getSharedPreferences("player_progress", MODE_PRIVATE).getLong(contentKey, C.TIME_UNSET)
            if (saved != C.TIME_UNSET) instance.seekTo(saved)
        }
        instance.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                logFailure("player_error", error)
                if (!attemptRecovery(error.errorCode in 2000..3999, allowFallback = true)) {
                    showUnavailable(getString(R.string.playback_error_detail))
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                timeoutHandler.removeCallbacks(stablePlayback)
                if (isPlaying) {
                    playerTitle.animate().alpha(0f).setStartDelay(1800).setDuration(300).start()
                    if (isLive) timeoutHandler.postDelayed(stablePlayback, STABLE_PLAYBACK_RESET_MS)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                failureStage = when (playbackState) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> failureStage
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (hasReachedReady && instance.playWhenReady && !userPaused) {
                            playbackLoadingMessage.setText(
                                if (isLive) R.string.reconnecting_channel else R.string.opening_video,
                            )
                            playbackLoading.isVisible = true
                            if (isLive) {
                                timeoutHandler.removeCallbacks(rebufferTimeout)
                                timeoutHandler.postDelayed(rebufferTimeout, LIVE_REBUFFER_TIMEOUT_MS)
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        hasReachedReady = true
                        timeoutHandler.removeCallbacks(startupTimeout)
                        timeoutHandler.removeCallbacks(rebufferTimeout)
                        timeoutHandler.removeCallbacks(automaticRetry)
                        playbackLoading.isVisible = false
                        playbackError.isVisible = false
                        recordSuccess()
                    }
                    Player.STATE_ENDED -> if (isLive && !userPaused) {
                        logFailure("live_stream_ended", null)
                        if (!attemptRecovery(recoverable = true, allowFallback = true)) {
                            showUnavailable(getString(R.string.playback_error_detail))
                        }
                    }
                }
            }
        })
        playbackRetry.setOnClickListener {
            playerTitle.animate().cancel()
            playerTitle.alpha = 1f
            playbackError.isVisible = false
            playbackLoading.isVisible = true
            release()
            failureRecorded = false
            successRecorded = false
            fallbackAttempted = false
            hasReachedReady = false
            userPaused = false
            recoveryPolicy.onStablePlayback()
            currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
            initialize()
        }
        playbackBack.setOnClickListener { finish() }
        playbackLoading.isVisible = true
        failureStage = "preparing"
        instance.prepare()
        instance.playWhenReady = !userPaused
        timeoutHandler.removeCallbacks(startupTimeout)
        timeoutHandler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS)
    }

    private fun attemptRecovery(recoverable: Boolean, allowFallback: Boolean): Boolean {
        val retryDelay = if (allowFallback && switchToFallbackUrl()) {
            0L
        } else {
            recoveryPolicy.nextRetryDelayMillis(recoverable) ?: return false
        }
        failureStage = "retry_wait"
        timeoutHandler.removeCallbacks(startupTimeout)
        timeoutHandler.removeCallbacks(rebufferTimeout)
        timeoutHandler.removeCallbacks(stablePlayback)
        player?.stop()
        playbackError.isVisible = false
        playbackLoading.isVisible = true
        playbackLoadingMessage.setText(if (isLive) R.string.reconnecting_channel else R.string.opening_video)
        timeoutHandler.removeCallbacks(automaticRetry)
        timeoutHandler.postDelayed(automaticRetry, retryDelay)
        return true
    }

    private fun configureSubtitleRendering() {
        playerView.subtitleView?.apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
            val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_TELEVISION
            setBottomPaddingFraction(
                if (television) TV_SUBTITLE_BOTTOM_PADDING_FRACTION
                else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
            )
        }
    }

    private fun showPlayerSettings() {
        val activePlayer = player ?: return
        val entries = buildList {
            add(PlayerSetting.PLAYBACK_SPEED)
            if (supportedTrackGroups(activePlayer.currentTracks, C.TRACK_TYPE_AUDIO).isNotEmpty()) {
                add(PlayerSetting.AUDIO)
            }
            if (supportedTrackGroups(activePlayer.currentTracks, C.TRACK_TYPE_TEXT).isNotEmpty()) {
                add(PlayerSetting.SUBTITLES)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_settings)
            .setItems(entries.map { getString(it.title) }.toTypedArray()) { _, position ->
                when (entries[position]) {
                    PlayerSetting.PLAYBACK_SPEED -> showPlaybackSpeedSettings(activePlayer)
                    PlayerSetting.AUDIO -> showTrackSettings(activePlayer, C.TRACK_TYPE_AUDIO)
                    PlayerSetting.SUBTITLES -> showTrackSettings(activePlayer, C.TRACK_TYPE_TEXT)
                }
            }
            .setNegativeButton(R.string.close_settings, null)
            .show()
    }

    private fun showPlaybackSpeedSettings(activePlayer: Player) {
        val checked = PLAYBACK_SPEEDS.indices.minByOrNull { index ->
            kotlin.math.abs(PLAYBACK_SPEEDS[index] - activePlayer.playbackParameters.speed)
        } ?: DEFAULT_PLAYBACK_SPEED_INDEX
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_speed)
            .setSingleChoiceItems(PLAYBACK_SPEED_LABELS, checked) { dialog, position ->
                activePlayer.setPlaybackSpeed(PLAYBACK_SPEEDS[position])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close_settings, null)
            .show()
    }

    private fun showTrackSettings(activePlayer: Player, trackType: Int) {
        val groups = supportedTrackGroups(activePlayer.currentTracks, trackType)
        if (groups.isEmpty()) return
        val groupSet = groups.map(Tracks.Group::getMediaTrackGroup).toSet()
        val current = activePlayer.trackSelectionParameters
        val currentOverrides = current.overrides.filterKeys(groupSet::contains)
        TrackSelectionDialogBuilder(
            this,
            getString(if (trackType == C.TRACK_TYPE_TEXT) R.string.subtitles else R.string.audio_tracks),
            groups,
        ) { disabled, overrides ->
            val updated = activePlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, disabled)
            overrides.values.forEach { override: TrackSelectionOverride -> updated.addOverride(override) }
            activePlayer.trackSelectionParameters = updated.build()
        }
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .setIsDisabled(trackType in current.disabledTrackTypes)
            .setOverrides(currentOverrides)
            .build()
            .show()
    }

    private fun externalSubtitles(): List<ExternalSubtitle> {
        val uris = intent.getStringArrayListExtra(EXTRA_SUBTITLE_URIS).orEmpty()
        val mimeTypes = intent.getStringArrayListExtra(EXTRA_SUBTITLE_MIME_TYPES).orEmpty()
        val languages = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
        val labels = intent.getStringArrayListExtra(EXTRA_SUBTITLE_LABELS).orEmpty()
        val flags = intent.getIntArrayExtra(EXTRA_SUBTITLE_SELECTION_FLAGS) ?: intArrayOf()
        return uris.indices.mapNotNull { index ->
            ExternalSubtitle(
                uri = uris[index].takeIf(String::isNotBlank) ?: return@mapNotNull null,
                mimeType = mimeTypes.getOrNull(index)?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                language = languages.getOrNull(index)?.takeIf(String::isNotBlank),
                label = labels.getOrNull(index)?.takeIf(String::isNotBlank),
                selectionFlags = flags.getOrElse(index) { 0 },
            )
        }
    }

    private fun switchToFallbackUrl(): Boolean {
        if (fallbackAttempted) return false
        val fallback = intent.getStringExtra(EXTRA_FALLBACK_URL).orEmpty()
        if (fallback.isBlank() || fallback == currentUrl) return false
        fallbackAttempted = true
        currentUrl = fallback
        return true
    }

    private fun showUnavailable(detail: String) {
        timeoutHandler.removeCallbacks(startupTimeout)
        timeoutHandler.removeCallbacks(rebufferTimeout)
        timeoutHandler.removeCallbacks(stablePlayback)
        timeoutHandler.removeCallbacks(automaticRetry)
        player?.stop()
        playbackLoading.isVisible = false
        playbackErrorMessage.text = detail
        playbackError.isVisible = true
        playbackRetry.requestFocus()
        recordFailure()
    }

    private fun recordSuccess() {
        if (successRecorded || !intent.getBooleanExtra(EXTRA_LIVE, false)) return
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        if (playlistId.isNotBlank() && streamId.isNotBlank()) {
            availability.recordSuccess(playlistId, streamId)
            successRecorded = true
        }
    }

    private fun recordFailure() {
        if (failureRecorded || !intent.getBooleanExtra(EXTRA_LIVE, false)) return
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        if (playlistId.isNotBlank() && streamId.isNotBlank()) {
            availability.recordPlaybackFailure(playlistId, streamId)
            failureRecorded = true
        }
    }

    private fun logPrepare(url: String, mime: String?) {
        val uri = Uri.parse(url)
        Log.i(
            TAG,
            "Playback prepare kind=${contentKind()} streamId=${streamId()} scheme=${uri.scheme.orEmpty()} " +
                "host=${uri.host.orEmpty()} mime=${mime ?: "auto"} health=${healthStatus()}",
        )
    }

    private fun logFailure(event: String, error: PlaybackException?) {
        val httpCode = generateSequence(error?.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        Log.e(
            TAG,
            "Playback failure event=$event stage=$failureStage kind=${contentKind()} streamId=${streamId()} " +
                "playerState=${player?.playbackState ?: Player.STATE_IDLE} code=${error?.errorCodeName ?: "timeout"} " +
                "http=${httpCode ?: "none"} cause=${error?.cause?.javaClass?.simpleName ?: "none"} health=${healthStatus()}",
        )
    }

    private fun contentKind(): String = intent.getStringExtra(EXTRA_KIND).orEmpty().ifBlank {
        if (intent.getBooleanExtra(EXTRA_LIVE, false)) "live" else "video"
    }

    private fun streamId(): String = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty().ifBlank { "unknown" }

    private fun healthStatus(): String {
        if (!intent.getBooleanExtra(EXTRA_LIVE, false)) return "not_tracked"
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        return if (playlistId.isBlank() || streamId.isBlank()) "unknown"
        else availability.status(playlistId, streamId).name.lowercase()
    }

    private fun release() {
        timeoutHandler.removeCallbacks(startupTimeout)
        timeoutHandler.removeCallbacks(rebufferTimeout)
        timeoutHandler.removeCallbacks(stablePlayback)
        timeoutHandler.removeCallbacks(automaticRetry)
        player?.let { current ->
            if (resumeEnabled && current.duration > 0) {
                if (current.currentPosition < current.duration * .93) {
                    getSharedPreferences("player_progress", MODE_PRIVATE).edit().putLong(contentKey, current.currentPosition).apply()
                } else {
                    getSharedPreferences("player_progress", MODE_PRIVATE).edit().remove(contentKey).apply()
                }
            }
            current.release()
        }
        playerView.player = null
        player = null
        trackSelector = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playbackError.isVisible) return super.dispatchKeyEvent(event)
        // TV-008: when the Media3 controller is visible/focused, let it own D-pad navigation
        // so the user can move between play/pause, seek, and back controls. Only fall back to
        // global shortcuts when the controller is hidden.
        // Delegate the complete down/up pair for controller navigation. Previously only key-down
        // reached Media3; the matching OK/Enter key-up fell through to the global play/pause
        // shortcut, so activating ±15-second controls also paused playback.
        if (playerControllerOwnsKey(playerView.isControllerFullyVisible, event.keyCode)) {
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    player?.let { activePlayer ->
                        userPaused = activePlayer.playWhenReady
                        if (userPaused) {
                            timeoutHandler.removeCallbacks(rebufferTimeout)
                            playbackLoading.isVisible = false
                            activePlayer.pause()
                        } else {
                            activePlayer.play()
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN && !isLive) player?.seekBack()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN && !isLive) player?.seekForward()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (event.action == KeyEvent.ACTION_DOWN) playerView.showController()
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_BUFFER = "buffer"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_STREAM_ID = "stream_id"
        private const val EXTRA_KIND = "content_kind"
        private const val EXTRA_FALLBACK_URL = "fallback_url"
        private const val EXTRA_SUBTITLE_URIS = "subtitle_uris"
        private const val EXTRA_SUBTITLE_MIME_TYPES = "subtitle_mime_types"
        private const val EXTRA_SUBTITLE_LANGUAGES = "subtitle_languages"
        private const val EXTRA_SUBTITLE_LABELS = "subtitle_labels"
        private const val EXTRA_SUBTITLE_SELECTION_FLAGS = "subtitle_selection_flags"
        private const val PLAYBACK_USER_AGENT = "CrownMedia/1.0"
        private const val TAG = "CrownPlayer"
        private const val STARTUP_TIMEOUT_MS = 25_000L
        private const val LIVE_REBUFFER_TIMEOUT_MS = 20_000L
        private const val STABLE_PLAYBACK_RESET_MS = 30_000L
        private const val TV_SUBTITLE_BOTTOM_PADDING_FRACTION = 0.12f
        private const val DEFAULT_PLAYBACK_SPEED_INDEX = 3
        private val PLAYBACK_SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val PLAYBACK_SPEED_LABELS = arrayOf("0.25×", "0.5×", "0.75×", "Normal", "1.25×", "1.5×", "2×")
        internal const val SEEK_INCREMENT_MS = 15_000L
        internal val CONTROLLER_NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        private val PLAYBACK_HTTP_CLIENT = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 4
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        fun internalIntent(
            context: Context,
            url: String,
            title: String,
            live: Boolean,
            buffer: String = "normal",
            playlistId: String = "",
            streamId: String = "",
            contentKind: String = if (live) "live" else "video",
            fallbackUrl: String? = null,
            externalSubtitles: List<ExternalSubtitle> = emptyList(),
        ) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_RESUME, !live).putExtra(EXTRA_BUFFER, buffer)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId).putExtra(EXTRA_STREAM_ID, streamId)
                .putExtra(EXTRA_KIND, contentKind)
                .putExtra(EXTRA_FALLBACK_URL, fallbackUrl)
                .putStringArrayListExtra(EXTRA_SUBTITLE_URIS, ArrayList(externalSubtitles.map(ExternalSubtitle::uri)))
                .putStringArrayListExtra(EXTRA_SUBTITLE_MIME_TYPES, ArrayList(externalSubtitles.map(ExternalSubtitle::mimeType)))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES, ArrayList(externalSubtitles.map { it.language.orEmpty() }))
                .putStringArrayListExtra(EXTRA_SUBTITLE_LABELS, ArrayList(externalSubtitles.map { it.label.orEmpty() }))
                .putExtra(EXTRA_SUBTITLE_SELECTION_FLAGS, externalSubtitles.map(ExternalSubtitle::selectionFlags).toIntArray())

        fun launchExternal(context: Context, url: String, title: String, packageName: String?): Boolean {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")
                putExtra("title", title)
                putExtra("from_start", false)
                if (packageName != null) setPackage(packageName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
        }

        private fun hash(input: String): String = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}

private enum class PlayerSetting(val title: Int) {
    PLAYBACK_SPEED(R.string.playback_speed),
    AUDIO(R.string.audio_tracks),
    SUBTITLES(R.string.subtitles),
}

@UnstableApi
internal fun playerControllerOwnsKey(controllerFullyVisible: Boolean, keyCode: Int): Boolean =
    controllerFullyVisible && keyCode in PlayerActivity.CONTROLLER_NAVIGATION_KEYS
