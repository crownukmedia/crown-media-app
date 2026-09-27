package uk.crownmedia.player

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television-mdpi")
@UnstableApi
class PlayerLaunchRegressionTest {
    private var activity: PlayerActivity? = null

    @After
    fun tearDown() {
        activity?.finish()
    }

    @Test
    fun internalPlayerInitializesForLiveMovieAndEpisodeIntentsAndNavigatesBack() {
        listOf(
            Triple("live", true, "http://127.0.0.1/live.ts"),
            Triple("movie", false, "http://127.0.0.1/movie.mp4"),
            Triple("episode", false, "http://127.0.0.1/episode.mp4"),
        ).forEach { (kind, live, url) ->
            val intent = PlayerActivity.internalIntent(
                context = RuntimeEnvironment.getApplication(),
                url = url,
                title = kind,
                live = live,
                contentKind = kind,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val controller = Robolectric.buildActivity(PlayerActivity::class.java, intent)
                .create()
                .start()
                .resume()
            activity = controller.get()

            assertFalse(activity!!.isFinishing)
            val playerView = activity!!.findViewById<PlayerView>(R.id.player_view)
            assertNotNull(playerView)
            assertEquals(
                View.GONE,
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).visibility,
            )
            assertEquals(View.VISIBLE, activity!!.findViewById<View>(R.id.playback_loading).visibility)
            val player = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                .get(activity) as Player
            assertEquals(PlayerActivity.SEEK_INCREMENT_MS, player.seekBackIncrement)
            assertEquals(PlayerActivity.SEEK_INCREMENT_MS, player.seekForwardIncrement)
            val selector = PlayerActivity::class.java.getDeclaredField("trackSelector").apply { isAccessible = true }
                .get(activity) as DefaultTrackSelector
            assertFalse(selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
            assertFalse(selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            assertNotNull(playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings))
            assertEquals(
                View.GONE,
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle).visibility,
            )
            activity!!.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity!!.isFinishing)
            controller.pause().stop().destroy()
            activity = null
        }
    }

    @Test
    fun visibleControllerOwnsCompleteDpadControlSequenceButNotMediaSeekKeys() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        ).forEach { keyCode ->
            assertTrue(playerControllerOwnsKey(controllerFullyVisible = true, keyCode))
            assertFalse(playerControllerOwnsKey(controllerFullyVisible = false, keyCode))
        }
        assertFalse(playerControllerOwnsKey(true, KeyEvent.KEYCODE_MEDIA_REWIND))
        assertFalse(playerControllerOwnsKey(true, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test
    fun episodeQueueStartsAtRequestedEpisodeAndKeepsFollowingEpisodesInOnePlayer() {
        val queue = listOf(
            PlaybackQueueItem("http://127.0.0.1/s1e1.mp4", "Episode 1", "1"),
            PlaybackQueueItem("http://127.0.0.1/s1e2.mp4", "Episode 2", "2"),
            PlaybackQueueItem("http://127.0.0.1/s2e1.mp4", "Episode 3", "3"),
        )
        val intent = PlayerActivity.internalIntent(
            context = RuntimeEnvironment.getApplication(),
            url = queue[1].url,
            title = queue[1].title,
            live = false,
            streamId = queue[1].streamId,
            contentKind = "episode",
            playbackQueue = queue,
            playbackQueueIndex = 1,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val controller = Robolectric.buildActivity(PlayerActivity::class.java, intent).create().start().resume()
        activity = controller.get()
        val player = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            .get(activity) as Player

        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("2", player.currentMediaItem?.mediaId)
        assertEquals("Episode 2", activity!!.findViewById<TextView>(R.id.player_title).text.toString())

        player.seekToNextMediaItem()
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals("3", player.currentMediaItem?.mediaId)
        assertEquals("Episode 3", activity!!.findViewById<TextView>(R.id.player_title).text.toString())

        controller.pause().stop().destroy()
        activity = null
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
    fun mobilePlayerAlsoUsesOnlyTheCrownLoadingIndicator() {
        val intent = PlayerActivity.internalIntent(
            context = RuntimeEnvironment.getApplication(),
            url = "http://127.0.0.1/movie.mp4",
            title = "movie",
            live = false,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = Robolectric.buildActivity(PlayerActivity::class.java, intent).create().get()

        val playerView = activity!!.findViewById<PlayerView>(R.id.player_view)
        assertEquals(View.GONE, playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).visibility)
        assertEquals(View.VISIBLE, activity!!.findViewById<View>(R.id.playback_loading).visibility)
    }
}
