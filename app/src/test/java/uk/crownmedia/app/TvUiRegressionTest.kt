package uk.crownmedia.app

import android.app.UiModeManager
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import uk.crownmedia.core.model.ProviderCredentials
import uk.crownmedia.data.xtream.XtreamEpisode
import uk.crownmedia.data.xtream.XtreamSeriesDetails
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvUiRegressionTest {
    private lateinit var activity: MainActivity
    private lateinit var testStore: AppStore

    @Before
    fun setUp() {
        setTelevisionMode()
        LayoutSelection(RuntimeEnvironment.getApplication()).select(AppLayout.TELEVISION)
        testStore = AppStore(FakeSecureStore()).apply {
            save(
                "TV test",
                ProviderCredentials("http://example.invalid", "user", "password"),
                null,
                "ACTIVE",
                0,
                1,
                allowedFormats = listOf("ts", "m3u8"),
            )
        }
        MainActivity.storeFactory = { testStore }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.finish()
        LayoutSelection(RuntimeEnvironment.getApplication()).clear()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun homeUsesBalancedThreeByTwoCompositionAndPersistentSelection() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val sideNav = activity.findViewById<View>(R.id.side_nav)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, (grid.layoutManager as GridLayoutManager).spanCount)
        assertEquals(6, grid.adapter?.itemCount)
        assertTrue(activity.findViewById<View>(R.id.nav_home).isSelected)
        assertEquals(dp(88), sideNav.layoutParams.width)
        assertTrue(grid.hasFocus())
        assertFalse(activity.findViewById<View>(R.id.nav_home).hasFocus())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.action_reload).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.action_playlist).visibility)
    }

    @Test
    fun homeUsesDedicatedCategoryIconsInExistingTileOrder() {
        val adapter = activity.findViewById<RecyclerView>(R.id.content_grid).adapter as CatalogAdapter

        assertEquals(
            listOf(
                R.drawable.home_live_icon,
                R.drawable.home_movies_icon,
                R.drawable.home_series_icon,
                R.drawable.home_account_icon,
                R.drawable.home_reload_icon,
                R.drawable.home_playlist_icon,
            ),
            adapter.currentItems.map { it.localArtwork },
        )
    }

    @Test
    fun homeGridDpadUsesRowsAndOnlyEntersSidebarAtFirstColumn() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val rail = activity.findViewById<View>(R.id.side_nav)
        shadowOf(Looper.getMainLooper()).idle()

        fun focusedPosition(): Int = grid.getChildAdapterPosition(requireNotNull(grid.focusedChild))
        fun press(keyCode: Int) {
            requireNotNull(grid.focusedChild).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(0, focusedPosition())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, focusedPosition())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(4, focusedPosition())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(5, focusedPosition())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(5, focusedPosition())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(2, focusedPosition())

        requireNotNull(grid.findViewHolderForAdapterPosition(0)).itemView.requestFocus()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertTrue(activity.findViewById<View>(R.id.nav_home).hasFocus())
        assertEquals(dp(260), rail.layoutParams.width)
    }

    @Test
    fun heldDpadRepeatsNeverQueueStaleGridFocusMoves() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        shadowOf(Looper.getMainLooper()).idle()

        listOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT,
        ).forEach { keyCode ->
            repeat(250) { repeatCount ->
                val focused = grid.focusedChild ?: grid.findViewHolderForAdapterPosition(0)?.itemView
                requireNotNull(focused).dispatchKeyEvent(
                    KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeatCount),
                )
            }
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
            val focusedChild = grid.focusedChild
            if (focusedChild != null) {
                assertTrue(grid.getChildAdapterPosition(focusedChild) in 0 until requireNotNull(grid.adapter).itemCount)
            } else {
                val sideNav = activity.findViewById<View>(R.id.side_nav)
                assertTrue(
                    "Fast key $keyCode left focus outside the content/sidebar regions: ${activity.currentFocus?.id}",
                    grid.hasFocus() || sideNav.hasFocus(),
                )
            }
        }

        assertFalse(activity.isFinishing)
    }

    @Test
    fun homeTileNavigatesDirectlyToDestinationWithoutRailOrHomeFlash() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val rail = activity.findViewById<View>(R.id.side_nav)
        shadowOf(Looper.getMainLooper()).idle()
        val liveTile = requireNotNull(grid.focusedChild)

        liveTile.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Live TV", activity.findViewById<TextView>(R.id.screen_title).text.toString())
        assertTrue(activity.findViewById<View>(R.id.nav_live).isSelected)
        assertEquals(dp(88), rail.layoutParams.width)
        assertFalse(activity.findViewById<View>(R.id.nav_live).hasFocus())
        assertTrue((grid.adapter as CatalogAdapter).currentItems.none { it.kind == "home" })
    }

    @Test
    fun firstDestinationLoadingNeverRevealsThePreviousHomeGrid() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val state = activity.findViewById<View>(R.id.state_panel)
        shadowOf(Looper.getMainLooper()).idle()
        val liveTile = requireNotNull(grid.focusedChild)

        liveTile.performClick()

        assertEquals("Live TV", activity.findViewById<TextView>(R.id.screen_title).text.toString())
        assertEquals(View.VISIBLE, state.visibility)
        // Keep the grid measured but non-drawing until RecyclerView completes the destination
        // layout frame; outgoing Home holders must never be visible behind the loader.
        assertEquals(View.INVISIBLE, grid.visibility)
        assertEquals(null, grid.itemAnimator)
        assertTrue(activity.findViewById<View>(R.id.category_bar).isShown)
        assertTrue(
            activity.findViewById<View>(R.id.category_list).hasFocus() ||
                activity.findViewById<View>(R.id.search_box).hasFocus(),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.category_menu_button).visibility)
        assertTrue(listOf(R.id.nav_home, R.id.nav_live, R.id.nav_movies, R.id.nav_series, R.id.nav_search).none {
            activity.findViewById<View>(it).hasFocus()
        })
    }

    @Test
    fun destinationLoadingKeepsRailCollapsedAndFocusOutsideSidebar() {
        val rail = activity.findViewById<View>(R.id.side_nav)

        activity.findViewById<View>(R.id.nav_movies).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(dp(88), rail.layoutParams.width)
        assertTrue(activity.findViewById<View>(R.id.category_bar).isShown)
        assertTrue(
            activity.findViewById<View>(R.id.search_box).hasFocus() ||
                activity.findViewById<View>(R.id.category_list).hasFocus() ||
                activity.findViewById<View>(R.id.content_grid).hasFocus() ||
                activity.findViewById<View>(R.id.state_action).hasFocus(),
        )
        assertTrue(listOf(R.id.nav_home, R.id.nav_live, R.id.nav_movies, R.id.nav_series, R.id.nav_search).none {
            activity.findViewById<View>(it).hasFocus()
        })
    }

    @Test
    fun catalogShellUsesExplicitCrossRegionFocusAndReadableTvSizing() {
        activity.findViewById<View>(R.id.nav_live).performClick()
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val categories = activity.findViewById<RecyclerView>(R.id.category_list)
        val menu = activity.findViewById<View>(R.id.category_menu_button)
        val navHome = activity.findViewById<Button>(R.id.nav_home)

        assertEquals(4, (grid.layoutManager as GridLayoutManager).spanCount)
        assertEquals(R.id.category_list, grid.nextFocusLeftId)
        assertEquals(R.id.nav_live, categories.nextFocusLeftId)
        assertEquals(R.id.content_grid, categories.nextFocusRightId)
        assertEquals(R.id.nav_live, menu.nextFocusLeftId)
        assertEquals(R.id.content_grid, menu.nextFocusRightId)
        assertEquals(R.id.category_list, menu.nextFocusDownId)
        assertEquals(R.id.category_list, navHome.nextFocusRightId)
        val search = activity.findViewById<EditText>(R.id.search_box)
        assertEquals("Search live channels", search.hint.toString())
        assertEquals(R.id.category_list, search.nextFocusDownId)
        assertEquals(RecyclerView.VERTICAL, (categories.layoutManager as LinearLayoutManager).orientation)
        assertEquals(dp(211), activity.findViewById<View>(R.id.category_bar).layoutParams.width)
        assertEquals(0, activity.findViewById<View>(R.id.category_bar).layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, categories.layoutParams.width)
        assertEquals(0, categories.layoutParams.height)
        assertFalse(activity.findViewById<TextView>(R.id.nav_live).text.contains('\n'))
        val scaledDensity = activity.resources.displayMetrics.density * activity.resources.configuration.fontScale
        assertEquals(14f, navHome.textSize / scaledDensity, 0.1f)
    }

    @Test
    fun tvBrandAndCategoryGeometryFitTheirContainers() {
        val logo = activity.findViewById<ImageView>(R.id.brand_logo)
        val category = activity.layoutInflater.inflate(R.layout.item_category, FrameLayout(activity), false)

        assertEquals(dp(56), logo.layoutParams.width)
        assertEquals(dp(54), logo.layoutParams.height)
        assertEquals(dp(5), logo.paddingTop)
        assertEquals(ImageView.ScaleType.FIT_CENTER, logo.scaleType)
        assertEquals(dp(54), category.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, category.layoutParams.width)
        assertEquals(0, category.minimumWidth)
        assertEquals(dp(14), category.paddingStart)
        assertEquals(dp(12), category.paddingEnd)
        assertEquals(dp(44), activity.findViewById<View>(R.id.category_menu_button).layoutParams.width)
        assertEquals(dp(44), activity.findViewById<View>(R.id.category_menu_button).layoutParams.height)
        assertEquals(0, activity.findViewById<View>(R.id.category_list).layoutParams.height)
    }

    @Test
    fun tvSearchCategoriesAndStateUseNonOverlappingVerticalAnchors() {
        val categories = activity.findViewById<View>(R.id.category_bar)
        val state = activity.findViewById<View>(R.id.state_panel)
        val message = activity.findViewById<View>(R.id.state_message)
        val stateParams = state.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(0, (categories.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        assertEquals(R.id.top_bar, stateParams.topToBottom)
        assertEquals(R.id.category_bar, stateParams.startToEnd)
        assertEquals(0, stateParams.width)
        assertEquals(dp(480), stateParams.matchConstraintMaxWidth)
        assertEquals(dp(32), stateParams.marginStart)
        assertEquals(dp(32), stateParams.marginEnd)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, message.layoutParams.width)
    }

    @Test
    fun pagedHeadersRemainFixedAndVisibleForEveryDestinationLoadingState() {
        val topBar = activity.findViewById<View>(R.id.top_bar)
        val categories = activity.findViewById<View>(R.id.category_bar)
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val categoryParams = categories.layoutParams as ConstraintLayout.LayoutParams
        val gridParams = grid.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, (topBar.layoutParams as ConstraintLayout.LayoutParams).topToTop)
        assertEquals(R.id.top_bar, categoryParams.topToBottom)
        assertEquals(R.id.category_bar, gridParams.startToEnd)
        assertEquals(R.id.top_bar, gridParams.topToBottom)
        assertEquals(dp(16).toFloat(), topBar.elevation, 0.1f)
        assertEquals(dp(12).toFloat(), categories.elevation, 0.1f)
        assertNotNull(topBar.background)
        assertNotNull(categories.background)

        listOf(R.id.nav_live, R.id.nav_movies, R.id.nav_series).forEach { destination ->
            activity.findViewById<View>(destination).performClick()
            assertEquals(View.VISIBLE, categories.visibility)
            assertEquals(View.VISIBLE, topBar.visibility)
            assertEquals(View.INVISIBLE, grid.visibility)
        }
    }

    @Test
    fun tvNavigationPushesContentOnFocusAndCollapsesAfterFocusLeaves() {
        val rail = activity.findViewById<View>(R.id.side_nav)
        val panel = activity.findViewById<View>(R.id.tv_nav_panel)
        val home = activity.findViewById<MaterialButton>(R.id.nav_home)
        val content = activity.findViewById<View>(R.id.content_grid)
        val topBar = activity.findViewById<View>(R.id.top_bar)
        val categories = activity.findViewById<View>(R.id.category_bar)

        assertEquals(dp(88), rail.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, panel.layoutParams.width)
        assertEquals(R.id.side_nav, (topBar.layoutParams as ConstraintLayout.LayoutParams).startToEnd)
        assertEquals(R.id.side_nav, (categories.layoutParams as ConstraintLayout.LayoutParams).startToEnd)
        assertEquals(R.id.category_bar, (content.layoutParams as ConstraintLayout.LayoutParams).startToEnd)
        content.requestFocus()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(dp(88), rail.layoutParams.width)
        assertEquals("", home.text.toString())
        assertEquals(Gravity.CENTER, home.gravity)
        assertEquals(0, home.iconPadding)
        assertEquals(0, home.paddingLeft)
        assertEquals(0, home.paddingRight)

        home.requestFocus()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)

        assertEquals(dp(260), rail.layoutParams.width)
        assertEquals("Home", home.text.toString())
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, home.gravity)
        assertEquals(dp(16), home.iconPadding)

        content.requestFocus()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)

        assertEquals(dp(88), rail.layoutParams.width)
        assertEquals("", home.text.toString())
    }

    @Test
    fun tvBackFromContentSectionReturnsHomeWithoutFinishing() {
        activity.findViewById<View>(R.id.nav_live).performClick()

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.findViewById<View>(R.id.nav_home).isSelected)
        assertEquals("Welcome to Crown Media", activity.findViewById<TextView>(R.id.screen_title).text.toString())
        assertFalse(activity.isFinishing)
        assertTrue(activity.findViewById<View>(R.id.content_grid).hasFocus())
    }

    @Test
    fun nestedSeriesShowsBackControlAndRemoteBackRestoresDetailsHierarchy() {
        activity.findViewById<View>(R.id.nav_series).performClick()
        val card = CatalogCard("series-1", "series", "Test Series", null, "Series")
        val details = XtreamSeriesDetails(
            name = "Test Series",
            plot = "Plot",
            cover = null,
            backdrop = null,
            cast = null,
            genre = "Drama",
            rating = null,
            trailer = null,
            episodes = mapOf(
                1 to listOf(XtreamEpisode("episode-1", 1, 1, "Pilot", "mp4", null, null, "45m")),
            ),
        )
        val stateClass = Class.forName("uk.crownmedia.app.MainActivity\$SeriesDetailState")
        val constructor = stateClass.declaredConstructors.single().apply { isAccessible = true }
        val nested = constructor.newInstance(card, details, 1)
        MainActivity::class.java.getDeclaredField("nestedSeries").apply {
            isAccessible = true
            set(activity, nested)
        }
        MainActivity::class.java.getDeclaredMethod("renderSeriesDetails").apply {
            isAccessible = true
            invoke(activity)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val back = activity.findViewById<View>(R.id.category_menu_button)
        assertEquals(View.VISIBLE, back.visibility)
        assertEquals("Back to Series details", back.contentDescription.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Test Series", shadowOf(dialog).title.toString())
        assertEquals("Browse episodes", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Back", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun nestedSeriesRailSupportsCompleteRemoteFocusAndSelectionFlow() {
        activity.findViewById<View>(R.id.nav_series).performClick()
        val card = CatalogCard("series-rail", "series", "Remote Series", null, "Series")
        val details = XtreamSeriesDetails(
            name = "Remote Series",
            plot = "Plot",
            cover = null,
            backdrop = null,
            cast = null,
            genre = "Drama",
            rating = null,
            trailer = null,
            episodes = (1..3).associateWith { season ->
                listOf(XtreamEpisode("episode-$season", season, 1, "Season $season pilot", "mp4", null, null, "45m"))
            },
        )
        val stateClass = Class.forName("uk.crownmedia.app.MainActivity\$SeriesDetailState")
        val constructor = stateClass.declaredConstructors.single().apply { isAccessible = true }
        val nested = constructor.newInstance(card, details, 1)
        MainActivity::class.java.getDeclaredField("nestedSeries").apply {
            isAccessible = true
            set(activity, nested)
        }
        MainActivity::class.java.getDeclaredMethod("renderSeriesDetails").apply {
            isAccessible = true
            invoke(activity)
        }

        val categories = activity.findViewById<RecyclerView>(R.id.category_list)
        val content = activity.findViewById<RecyclerView>(R.id.content_grid)
        val back = activity.findViewById<View>(R.id.category_menu_button)

        fun awaitUi(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(condition())
        }

        awaitUi {
            categories.adapter?.itemCount == 3 &&
                content.adapter?.itemCount == 1 &&
                categories.findViewHolderForAdapterPosition(0) != null &&
                content.findViewHolderForAdapterPosition(0) != null
        }

        MainActivity::class.java.getDeclaredMethod("loadNextPage").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.page_progress).visibility)
        assertTrue((content.adapter as CatalogAdapter).currentItems.all { it.kind == "episode" })

        fun press(view: View, keyCode: Int) {
            assertTrue("D-pad key $keyCode was not consumed by ${view.id}", view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        }

        fun focusedSeasonPosition(): Int = categories.focusedChild
            ?.let(categories::getChildAdapterPosition)
            ?: RecyclerView.NO_POSITION

        val firstEpisode = requireNotNull(content.findViewHolderForAdapterPosition(0)).itemView
        firstEpisode.requestFocus()
        press(firstEpisode, KeyEvent.KEYCODE_DPAD_LEFT)
        awaitUi { categories.hasFocus() && focusedSeasonPosition() == 0 }
        assertEquals(0, focusedSeasonPosition())
        assertTrue(requireNotNull(categories.focusedChild).drawableState.contains(android.R.attr.state_focused))

        press(requireNotNull(categories.focusedChild), KeyEvent.KEYCODE_DPAD_UP)
        awaitUi { back.hasFocus() }
        press(back, KeyEvent.KEYCODE_DPAD_DOWN)
        awaitUi { focusedSeasonPosition() == 0 }
        assertEquals(0, focusedSeasonPosition())
        press(requireNotNull(categories.focusedChild), KeyEvent.KEYCODE_DPAD_DOWN)
        awaitUi { focusedSeasonPosition() == 1 }
        assertEquals(1, focusedSeasonPosition())

        press(requireNotNull(categories.focusedChild), KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, stateClass.getDeclaredField("season").apply { isAccessible = true }.getInt(nested))
        awaitUi { (content.adapter as CatalogAdapter).currentItems.firstOrNull()?.id == "episode-2" }
        assertTrue("Focus remained on ${activity.currentFocus?.id}", content.hasFocus())

        press(requireNotNull(content.focusedChild), KeyEvent.KEYCODE_DPAD_LEFT)
        awaitUi { focusedSeasonPosition() == 1 }
        assertEquals(1, focusedSeasonPosition())
        press(requireNotNull(categories.focusedChild), KeyEvent.KEYCODE_DPAD_UP)
        awaitUi { focusedSeasonPosition() == 0 }
        press(requireNotNull(categories.focusedChild), KeyEvent.KEYCODE_DPAD_UP)
        awaitUi { back.hasFocus() }

        press(back, KeyEvent.KEYCODE_DPAD_CENTER)
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Remote Series", shadowOf(dialog).title.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun removePlaylistRespondsImmediatelyAndReturnsToLogin() {
        activity.findViewById<View>(R.id.nav_account).performClick()
        val account = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Remove playlist", account.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        val playlist = requireNotNull(testStore.selected())
        MainActivity::class.java.getDeclaredMethod("confirmRemove", SavedPlaylist::class.java).apply {
            isAccessible = true
            invoke(activity, playlist)
        }

        val confirmation = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Remove TV test?", shadowOf(confirmation).title.toString())
        assertEquals("Remove", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        MainActivity::class.java.getDeclaredMethod("removePlaylist", SavedPlaylist::class.java).apply {
            isAccessible = true
            invoke(activity, playlist)
        }

        assertNull(testStore.selected())
        assertTrue(activity.findViewById<View>(R.id.login_panel).isShown)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.side_nav).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun tvBackCollapsesExpandedSidebarBeforeLeavingHome() {
        val rail = activity.findViewById<View>(R.id.side_nav)
        activity.findViewById<View>(R.id.nav_home).requestFocus()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(dp(260), rail.layoutParams.width)

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)

        assertEquals(dp(88), rail.layoutParams.width)
        assertTrue(activity.findViewById<View>(R.id.content_grid).hasFocus())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun tvBackAtHomeShowsExitConfirmation() {
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Exit Crown Media?", shadowOf(dialog).title.toString())
        assertEquals("Are you sure you want to exit the app?", shadowOf(dialog).message.toString())
        assertEquals("Exit", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun loginPrimaryDpadPathIncludesEveryRequiredCredentialField() {
        activity.finish()
        MainActivity.storeFactory = { AppStore(FakeSecureStore()) }
        setTelevisionMode()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val service = activity.findViewById<MaterialAutoCompleteTextView>(R.id.service_dropdown)
        val username = activity.findViewById<View>(R.id.username)
        val password = activity.findViewById<View>(R.id.password)
        val save = activity.findViewById<View>(R.id.save_login)
        val connect = activity.findViewById<View>(R.id.connect_button)
        val qr = activity.findViewById<View>(R.id.qr_button)

        assertEquals(R.id.username, service.nextFocusDownId)
        assertEquals(R.id.password, username.nextFocusDownId)
        assertEquals(R.id.save_login, password.nextFocusDownId)
        assertEquals(R.id.connect_button, save.nextFocusDownId)
        assertEquals(R.id.save_login, connect.nextFocusUpId)
        assertFalse(qr.isEnabled)
        assertEquals(View.GONE, qr.visibility)
        assertEquals(View.NO_ID, connect.nextFocusDownId)
    }

    @Test
    fun tvLoginLogoUsesContainedCenteredArtworkWithSafePadding() {
        activity.finish()
        MainActivity.storeFactory = { AppStore(FakeSecureStore()) }
        setTelevisionMode()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val logo = activity.findViewById<ImageView>(R.id.login_logo)
        val login = activity.findViewById<View>(R.id.login_panel)
        val loginContent = (login as ViewGroup).getChildAt(0) as LinearLayout
        val loginParams = login.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(ImageView.ScaleType.FIT_CENTER, logo.scaleType)
        assertEquals(dp(8), logo.paddingLeft)
        assertEquals(dp(8), logo.paddingTop)
        assertEquals(dp(8), logo.paddingRight)
        assertEquals(dp(8), logo.paddingBottom)
        assertEquals(0, loginParams.width)
        assertEquals(dp(720), loginParams.matchConstraintMaxWidth)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, loginParams.startToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, loginParams.endToEnd)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, loginParams.topToTop)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, loginParams.bottomToBottom)
        assertEquals(Gravity.CENTER, loginContent.gravity)
    }

    @Test
    fun firstTvLaunchRequestsConsentForDetectedLayout() {
        activity.finish()
        LayoutSelection(RuntimeEnvironment.getApplication()).clear()
        MainActivity.storeFactory = { AppStore(FakeSecureStore()) }
        setTelevisionMode()

        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.toString().contains("Settings"))
        assertEquals("Use TV layout", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Use mobile layout", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
    }

    @Test
    fun settingsExposesRecoverableAutoMobileAndTvLayoutChoices() {
        activity.findViewById<View>(R.id.nav_settings).performClick()

        val settingsDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("App layout • TV", settingsDialog.listView.adapter.getItem(6).toString())
        requireNotNull(settingsDialog.listView.onItemClickListener)
            .onItemClick(settingsDialog.listView, null, 6, 6L)

        val layoutDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Auto-detect", layoutDialog.listView.adapter.getItem(0).toString())
        assertEquals("Mobile", layoutDialog.listView.adapter.getItem(1).toString())
        assertEquals("TV", layoutDialog.listView.adapter.getItem(2).toString())
    }

    @Test
    fun tvDialogButtonsUseWhiteTextAndPinkFocusedBackground() {
        activity.findViewById<View>(R.id.nav_exit).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) as MaterialButton
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE) as MaterialButton
        val white = ContextCompat.getColor(activity, R.color.white)
        val pink = ContextCompat.getColor(activity, R.color.crown_primary_bright)

        positive.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(white, positive.currentTextColor)
        assertEquals(pink, positive.backgroundTintList?.getColorForState(intArrayOf(android.R.attr.state_focused), 0))

        negative.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(white, negative.currentTextColor)
        assertEquals(pink, negative.backgroundTintList?.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
        assertFalse(positive.hasFocus())
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun setTelevisionMode() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(manager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
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
