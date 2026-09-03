package uk.crownmedia.player

import android.content.Intent
import android.view.View
import org.junit.After
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
            assertNotNull(activity!!.findViewById<View>(R.id.player_view))
            activity!!.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity!!.isFinishing)
            controller.pause().stop().destroy()
            activity = null
        }
    }
}
