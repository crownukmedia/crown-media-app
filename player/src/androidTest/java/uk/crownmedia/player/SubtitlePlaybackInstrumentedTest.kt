package uk.crownmedia.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class SubtitlePlaybackInstrumentedTest {
    @Test
    fun hlsWebVttTrackIsDiscoveredButNotForcedOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val tracksReady = CountDownLatch(1)
        val capturedTracks = AtomicReference<Tracks>()
        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(targetContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(testContext)))
                .build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (supportedTrackGroups(tracks, C.TRACK_TYPE_TEXT).isNotEmpty()) {
                        capturedTracks.set(tracks)
                        tracksReady.countDown()
                    }
                }
            })
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri("asset:///hls/master.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
            player.prepare()
        }

        try {
            assertTrue("HLS WebVTT track was not discovered", tracksReady.await(20, TimeUnit.SECONDS))
            val textGroups = supportedTrackGroups(requireNotNull(capturedTracks.get()), C.TRACK_TYPE_TEXT)
            assertEquals("en", textGroups.single().getTrackFormat(0).language)
            assertFalse("HLS subtitles must remain off when DEFAULT=NO", textGroups.any(Tracks.Group::isSelected))
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test
    fun embeddedSubtitlesStartOffAndSwitchWithoutRestartingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val tracksReady = CountDownLatch(1)
        val spanishSelected = CountDownLatch(1)
        val spanishCue = CountDownLatch(1)
        val capturedTracks = AtomicReference<Tracks>()
        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(targetContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(testContext)))
                .build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val textGroups = supportedTrackGroups(tracks, C.TRACK_TYPE_TEXT)
                    if (textGroups.sumOf(Tracks.Group::length) >= 2) {
                        capturedTracks.set(tracks)
                        if (textGroups.any(Tracks.Group::isSelected)) spanishSelected.countDown()
                        tracksReady.countDown()
                    }
                }

                override fun onCues(cueGroup: CueGroup) {
                    if (cueGroup.cues.any { it.text?.contains("español") == true }) spanishCue.countDown()
                }
            })
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("asset:///subtitle-multitrack.mkv"))
            player.playWhenReady = true
            player.prepare()
        }

        try {
            assertTrue("Subtitle tracks were not discovered", tracksReady.await(20, TimeUnit.SECONDS))
            val textGroups = supportedTrackGroups(requireNotNull(capturedTracks.get()), C.TRACK_TYPE_TEXT)
            assertEquals(2, textGroups.sumOf(Tracks.Group::length))
            assertFalse("Subtitles must start off when the content has no default", textGroups.any(Tracks.Group::isSelected))

            val mediaItem = player.currentMediaItem
            val spanish = textGroups.first { group ->
                (0 until group.length).any { group.getTrackFormat(it).language == "spa" }
            }
            val spanishIndex = (0 until spanish.length).first { spanish.getTrackFormat(it).language == "spa" }
            instrumentation.runOnMainSync {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(spanish.mediaTrackGroup, spanishIndex))
                    .build()
            }

            assertTrue("Spanish subtitle track was not selected", spanishSelected.await(10, TimeUnit.SECONDS))
            assertSame("Track switching must keep the same media item", mediaItem, player.currentMediaItem)
            assertTrue("Spanish cues were not rendered", spanishCue.await(10, TimeUnit.SECONDS))

            instrumentation.runOnMainSync {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test
    fun sideLoadedSrtIsAttachedWithItsRealLanguageAndLabel() {
        val external = requireNotNull(
            ExternalSubtitle.fromProvider(
                uri = "asset:///subtitles-en.srt",
                language = "en",
                label = "English",
            ),
        )

        val item = buildMediaItem("asset:///subtitle-multitrack.mkv", null, listOf(external))
        val configuration = requireNotNull(item.localConfiguration).subtitleConfigurations.single()

        assertEquals("en", configuration.language)
        assertEquals("English", configuration.label)
        assertEquals(0, configuration.selectionFlags)
    }
}
