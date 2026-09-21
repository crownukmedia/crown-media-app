package uk.crownmedia.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class DolbyAudioDecoderInstrumentedTest {
    @Test
    fun ffmpegFallbackSelectsDefaultEac3AndExposesAc3Alternative() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val ready = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        val tracks = AtomicReference<Tracks?>()
        lateinit var player: ExoPlayer

        assertTrue(FfmpegLibrary.isAvailable())
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3))
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3))

        instrumentation.runOnMainSync {
            val renderers = DefaultRenderersFactory(targetContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            player = ExoPlayer.Builder(targetContext, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(testContext)))
                .build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(playbackException: PlaybackException) {
                    error.set(playbackException)
                    ready.countDown()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        tracks.set(player.currentTracks)
                        ready.countDown()
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri("asset:///dolby-multitrack.mkv"))
            player.playWhenReady = true
            player.prepare()
        }

        try {
            assertTrue("Dolby sample did not become ready", ready.await(20, TimeUnit.SECONDS))
            assertNull(error.get())
            val audioGroups = requireNotNull(tracks.get()).groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            assertTrue("Expected E-AC-3 and AC-3 tracks", audioGroups.sumOf(Tracks.Group::length) >= 2)
            assertTrue("A supported audio track must be selected automatically", audioGroups.any { group ->
                (0 until group.length).any { index ->
                    group.isTrackSelected(index) && group.isTrackSupported(index)
                }
            })
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }
}
