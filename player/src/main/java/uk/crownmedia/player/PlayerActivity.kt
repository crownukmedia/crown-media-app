package uk.crownmedia.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import uk.crownmedia.core.design.StreamAvailability
import java.security.MessageDigest

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
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val startupTimeout = Runnable {
        logFailure("startup_timeout", null)
        showUnavailable(getString(R.string.playback_timeout_detail))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
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
        val live = intent.getBooleanExtra(EXTRA_LIVE, false)
        playbackLoadingMessage.setText(if (live) R.string.opening_channel else R.string.opening_video)
        playbackBack.setText(if (live) R.string.back_to_channels else R.string.back_to_content)
        contentKey = hash(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onStart() { super.onStart(); initialize() }
    override fun onStop() { release(); super.onStop() }

    private fun initialize() {
        if (player != null) return
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        failureStage = "initializing"
        val buffer = intent.getStringExtra(EXTRA_BUFFER) ?: "normal"
        val loadControl = when (buffer) {
            "low" -> DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 500, 1_000).build()
            "resilient" -> DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 90_000, 2_500, 5_000).build()
            else -> DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 45_000, 1_500, 3_000).build()
        }
        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(PLAYBACK_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
        val dataSource = DefaultDataSource.Factory(this, httpDataSource)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        val instance = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = instance
        playerView.player = instance
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
        logPrepare(url, mime)
        instance.setMediaItem(item)
        if (resumeEnabled) {
            val saved = getSharedPreferences("player_progress", MODE_PRIVATE).getLong(contentKey, C.TIME_UNSET)
            if (saved != C.TIME_UNSET) instance.seekTo(saved)
        }
        instance.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                logFailure("player_error", error)
                showUnavailable(getString(R.string.playback_error_detail))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playerTitle.animate().alpha(0f).setStartDelay(1800).setDuration(300).start()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                failureStage = when (playbackState) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> failureStage
                }
                if (playbackState == Player.STATE_READY) {
                    timeoutHandler.removeCallbacks(startupTimeout)
                    playbackLoading.isVisible = false
                    playbackError.isVisible = false
                    recordSuccess()
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
            initialize()
        }
        playbackBack.setOnClickListener { finish() }
        playbackLoading.isVisible = true
        failureStage = "preparing"
        instance.prepare()
        instance.playWhenReady = true
        timeoutHandler.removeCallbacks(startupTimeout)
        timeoutHandler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS)
    }

    private fun showUnavailable(detail: String) {
        timeoutHandler.removeCallbacks(startupTimeout)
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
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playbackError.isVisible) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }; return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (intent.getBooleanExtra(EXTRA_LIVE, false).not()) player?.seekBack(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (intent.getBooleanExtra(EXTRA_LIVE, false).not()) player?.seekForward(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> playerView.showController()
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
        private const val PLAYBACK_USER_AGENT = "CrownMedia/1.0"
        private const val TAG = "CrownPlayer"
        private const val STARTUP_TIMEOUT_MS = 25_000L

        fun internalIntent(
            context: Context,
            url: String,
            title: String,
            live: Boolean,
            buffer: String = "normal",
            playlistId: String = "",
            streamId: String = "",
            contentKind: String = if (live) "live" else "video",
        ) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_RESUME, !live).putExtra(EXTRA_BUFFER, buffer)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId).putExtra(EXTRA_STREAM_ID, streamId)
                .putExtra(EXTRA_KIND, contentKind)

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
