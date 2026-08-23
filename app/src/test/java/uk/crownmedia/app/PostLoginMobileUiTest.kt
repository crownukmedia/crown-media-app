package uk.crownmedia.app

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.crownmedia.core.model.ProviderCredentials

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "port")
class PostLoginMobileUiTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        val store = AppStore(FakeSecureStore()).apply {
            save(
                "Mobile test",
                ProviderCredentials("http://example.invalid", "user", "password"),
                null,
                "ACTIVE",
                0,
                1,
                allowedFormats = listOf("ts", "m3u8"),
            )
        }
        MainActivity.storeFactory = { store }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.finish()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun authenticatedHeaderUsesCompactCroppedLogo() {
        val density = activity.resources.displayMetrics.density
        val logo = activity.findViewById<ImageView>(R.id.brand_logo)
        val topBar = activity.findViewById<View>(R.id.top_bar)

        assertEquals((58 * density).toInt(), logo.layoutParams.width)
        assertEquals((44 * density).toInt(), logo.layoutParams.height)
        assertEquals((2 * density).toInt(), logo.paddingTop)
        assertEquals((54 * density).toInt(), topBar.minimumHeight)
    }

    @Test
    fun loadingStateContainsOnlyLoadingLabelAndIndicator() {
        activity.findViewById<View>(R.id.nav_live).performClick()

        val panel = activity.findViewById<ViewGroup>(R.id.state_panel)
        assertEquals("Loading…", activity.findViewById<TextView>(R.id.state_title).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progress).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.state_message).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.state_action).visibility)
        assertFalse((0 until panel.childCount).any { panel.getChildAt(it) is ImageView })
    }

    @Test
    fun mobileCardsAndCategoryActionsKeepCompactTouchFriendlySizing() {
        val density = activity.resources.displayMetrics.density
        val card = LayoutInflater.from(activity).inflate(R.layout.item_content, null, false)
        val artworkContainer = card.findViewById<ImageView>(R.id.artwork).parent as View
        val category = LayoutInflater.from(activity).inflate(R.layout.item_category, null, false)
        val categoryAction = category.findViewById<View>(R.id.category_actions)

        assertEquals((104 * density).toInt(), artworkContainer.layoutParams.height)
        assertEquals((48 * density).toInt(), categoryAction.layoutParams.width)
        assertEquals((48 * density).toInt(), categoryAction.layoutParams.height)
        assertTrue(activity.findViewById<View>(R.id.side_nav).isShown)
    }

    @Test
    @Config(sdk = [28], qualifiers = "land")
    fun compactHeaderAndNavigationRemainVisibleInMobileLandscape() {
        val density = activity.resources.displayMetrics.density
        val logo = activity.findViewById<ImageView>(R.id.brand_logo)

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
        assertEquals((44 * density).toInt(), logo.layoutParams.height)
        assertTrue(logo.isShown)
        assertTrue(activity.findViewById<View>(R.id.side_nav).isShown)
        assertTrue(activity.findViewById<View>(R.id.content_grid).isShown)
    }

    private class FakeSecureStore : CrownSecureStore {
        private val strings = mutableMapOf<String, String?>()
        private val sets = mutableMapOf<String, Set<String>>()
        override fun getString(key: String, fallback: String?): String? = strings[key] ?: fallback
        override fun putString(key: String, value: String?) { strings[key] = value }
        override fun getStringSet(key: String): Set<String> = sets[key].orEmpty()
        override fun putStringSet(key: String, value: Set<String>) { sets[key] = value }
    }
}
