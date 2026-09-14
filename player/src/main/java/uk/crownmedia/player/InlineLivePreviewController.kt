package uk.crownmedia.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns at most one muted Live stream and moves it between card surfaces. It intentionally has no
 * retry/fallback loop: preview failure returns to artwork and never affects full playback.
 */
@UnstableApi
class InlineLivePreviewController(
    context: Context,
    private val onPreviewEnded: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var host: InlineLivePreviewView? = null
    private var activeMediaId: String? = null
    private var generation = 0L
    private val timeout = Runnable { failActivePreview() }

    fun start(target: InlineLivePreviewView, url: String) {
        if (url.isBlank()) return
        stop()
        generation++
        val requestGeneration = generation
        activeMediaId = requestGeneration.toString()
        val instance = player ?: buildPlayer().also { player = it }
        val previewSurface = playerView ?: buildPlayerView().also { playerView = it }
        host = target
        target.prepare(previewSurface)
        previewSurface.player = instance
        instance.setMediaItem(mediaItem(url, activeMediaId.orEmpty()))
        instance.volume = 0f
        instance.prepare()
        instance.playWhenReady = true
        handler.postDelayed(timeout, PREVIEW_TIMEOUT_MS)

    }

    fun stop() {
        generation++
        handler.removeCallbacks(timeout)
        player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
        host?.reset()
        host = null
        activeMediaId = null
    }

    fun release() {
        stop()
        player?.release()
        player = null
        playerView = null
    }

    private fun buildPlayer(): ExoPlayer {
        val httpSource = OkHttpDataSource.Factory(PREVIEW_HTTP_CLIENT)
            .setUserAgent(PREVIEW_USER_AGENT)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Accept-Encoding" to "identity"))
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, httpSource))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 6_000, 400, 800)
            .build()
        return ExoPlayer.Builder(
            appContext,
            DefaultRenderersFactory(appContext).setEnableDecoderFallback(true),
        )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                setHandleAudioBecomingNoisy(false)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (player?.currentMediaItem?.mediaId != activeMediaId) return
                        handler.removeCallbacks(timeout)
                        host?.reveal()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failActivePreview()
                    }
                })
            }
    }

    private fun buildPlayerView(): PlayerView =
        (LayoutInflater.from(appContext).inflate(R.layout.view_inline_live_preview, null, false) as PlayerView).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setKeepContentOnPlayerReset(true)
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    private fun failActivePreview() {
        if (activeMediaId == null) return
        stop()
        onPreviewEnded()
    }

    private fun mediaItem(url: String, mediaId: String): MediaItem {
        val mimeType = when {
            Uri.parse(url).lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true -> MimeTypes.APPLICATION_M3U8
            Uri.parse(url).lastPathSegment?.endsWith(".mpd", ignoreCase = true) == true -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return MediaItem.Builder().setMediaId(mediaId).setUri(url).apply {
            if (mimeType != null) setMimeType(mimeType)
        }.build()
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 10_000L
        const val PREVIEW_USER_AGENT = "CrownMedia/1.0"
        val PREVIEW_HTTP_CLIENT = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 2
                maxRequestsPerHost = 1
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
