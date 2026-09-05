package uk.crownmedia.app

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.EditText
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import kotlinx.coroutines.runBlocking
import uk.crownmedia.core.database.CrownDatabase
import uk.crownmedia.core.model.ProviderCredentials
import uk.crownmedia.data.xtream.XtreamItem
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "port")
class PostLoginMobileUiTest {
    private lateinit var activity: MainActivity
    private lateinit var testStore: AppStore

    @Before
    fun setUp() {
        testStore = AppStore(FakeSecureStore()).apply {
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
        runBlocking {
            CatalogCache(CrownDatabase.get(RuntimeEnvironment.getApplication()).catalogDao())
                .deletePlaylist(testStore.selected()!!.id)
        }
        MainActivity.storeFactory = { testStore }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.finish()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun authenticatedHeaderUsesCompactProportionalLogo() {
        val density = activity.resources.displayMetrics.density
        val logo = activity.findViewById<ImageView>(R.id.brand_logo)
        val topBar = activity.findViewById<View>(R.id.top_bar)

        assertEquals((58 * density).toInt(), logo.layoutParams.width)
        assertEquals((44 * density).toInt(), logo.layoutParams.height)
        assertEquals((4 * density).toInt(), logo.paddingTop)
        assertEquals(ImageView.ScaleType.FIT_CENTER, logo.scaleType)
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
    fun contentSectionsExposeScopedSearchWithoutReplacingMasterSearch() {
        activity.findViewById<View>(R.id.nav_live).performClick()

        val search = activity.findViewById<EditText>(R.id.search_box)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.search_row).visibility)
        assertEquals("Search live channels", search.hint.toString())

        activity.findViewById<View>(R.id.nav_search).performClick()

        assertEquals("Search live TV, movies and series", search.hint.toString())
    }

    @Test
    fun sectionChangesResetDedicatedSearchAndActiveFilterState() {
        activity.findViewById<View>(R.id.nav_live).performClick()
        val search = activity.findViewById<EditText>(R.id.search_box)
        search.setText("sport")

        activity.findViewById<View>(R.id.nav_movies).performClick()
        assertEquals("", search.text.toString())
        assertEquals("Search movies", search.hint.toString())

        search.setText("drama")
        activity.findViewById<View>(R.id.nav_live).performClick()
        assertEquals("", search.text.toString())
        assertEquals("Search live channels", search.hint.toString())
    }

    @Test
    fun mobileScopedAndMasterSearchResultsSurvivePlaybackReturn() {
        val playlist = requireNotNull(testStore.selected())
        val cache = CatalogCache(CrownDatabase.get(RuntimeEnvironment.getApplication()).catalogDao())
        runBlocking {
            cache.saveItems(
                playlist.id,
                "movie",
                null,
                listOf(
                    searchItem("movie-match", "Needle movie"),
                    searchItem("movie-other", "Unrelated movie"),
                ),
            )
            listOf("live", "movie", "series").forEach {
                testStore.markCatalogRefreshed(playlist.id, it, null)
            }
        }

        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val adapter = grid.adapter as CatalogAdapter
        val search = activity.findViewById<EditText>(R.id.search_box)

        fun awaitUi(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            }
            assertTrue(condition())
        }

        activity.findViewById<View>(R.id.nav_movies).performClick()
        awaitUi { adapter.currentItems.size == 2 }
        val baseCards = adapter.currentItems.toList()
        search.setText("needle")
        awaitUi { adapter.currentItems.map(CatalogCard::id) == listOf("movie-match") }
        adapter.submit(baseCards)
        awaitUi { adapter.currentItems.size == 2 }
        MainActivity::class.java.getDeclaredMethod("restoreSearchPresentationAfterPlayback").apply {
            isAccessible = true
            invoke(activity)
        }
        awaitUi { adapter.currentItems.map(CatalogCard::id) == listOf("movie-match") }
        assertEquals("needle", search.text.toString())

        activity.findViewById<View>(R.id.nav_search).performClick()
        search.setText("needle")
        awaitUi { adapter.currentItems.map(CatalogCard::id) == listOf("movie-match") }
        adapter.submit(listOf(CatalogCard("reset", "home", "Reset", null, "")))
        awaitUi { adapter.currentItems.singleOrNull()?.id == "reset" }
        MainActivity::class.java.getDeclaredMethod("restoreSearchPresentationAfterPlayback").apply {
            isAccessible = true
            invoke(activity)
        }
        awaitUi { adapter.currentItems.map(CatalogCard::id) == listOf("movie-match") }
        assertEquals("needle", search.text.toString())
    }

    @Test
    fun mobileBackReturnsFromSectionThenConfirmsExitAtHome() {
        activity.findViewById<View>(R.id.nav_live).performClick()

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(activity.findViewById<View>(R.id.nav_home).isSelected)
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Exit Crown Media?", shadowOf(dialog).title.toString())
        assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun searchAndCategoryRowsHaveIndependentVerticalSpace() {
        val density = activity.resources.displayMetrics.density
        val categoryBar = activity.findViewById<ViewGroup>(R.id.category_bar)
        val menu = activity.findViewById<View>(R.id.category_menu_button)
        val categories = activity.findViewById<RecyclerView>(R.id.category_list)
        val state = activity.findViewById<View>(R.id.state_panel)

        assertEquals((4 * density).toInt(), (categoryBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        assertEquals((56 * density).toInt(), categoryBar.layoutParams.height)
        assertEquals(categoryBar, menu.parent)
        assertEquals(categoryBar, categories.parent)
        assertEquals(0, categoryBar.indexOfChild(menu))
        assertEquals(1, categoryBar.indexOfChild(categories))
        assertEquals(RecyclerView.HORIZONTAL, (categories.layoutManager as LinearLayoutManager).orientation)
        assertEquals(R.id.category_bar, (state.layoutParams as ConstraintLayout.LayoutParams).topToBottom)
    }

    @Test
    fun mobileCategoryButtonsUseCompactContentBasedSizing() {
        val density = activity.resources.displayMetrics.density
        val category = activity.layoutInflater.inflate(R.layout.item_category, FrameLayout(activity), false) as ViewGroup
        val name = category.findViewById<TextView>(R.id.category_name)
        val actions = category.findViewById<View>(R.id.category_actions)
        val menu = activity.findViewById<View>(R.id.category_menu_button)

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, category.layoutParams.width)
        assertEquals(0, category.minimumWidth)
        assertEquals((12 * density).toInt(), name.paddingStart)
        assertEquals((12 * density).toInt(), name.paddingEnd)
        assertEquals((48 * density).toInt(), actions.layoutParams.width)
        assertEquals((48 * density).toInt(), menu.layoutParams.width)
        assertEquals((48 * density).toInt(), activity.findViewById<View>(R.id.category_list).layoutParams.height)
    }

    @Test
    fun mobileNavigationReservesCountLineForEachContentType() {
        assertTrue(activity.findViewById<TextView>(R.id.nav_live).text.startsWith("Live\n("))
        assertTrue(activity.findViewById<TextView>(R.id.nav_movies).text.startsWith("Movies\n("))
        assertTrue(activity.findViewById<TextView>(R.id.nav_series).text.startsWith("Series\n("))
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

    private fun searchItem(id: String, title: String) = XtreamItem(
        id = id,
        categoryId = "test",
        name = title,
        imageUrl = null,
        rating = null,
        addedEpochSeconds = null,
        extension = "mp4",
        epgChannelId = null,
        catchUp = false,
        catchUpDays = 0,
    )
}
