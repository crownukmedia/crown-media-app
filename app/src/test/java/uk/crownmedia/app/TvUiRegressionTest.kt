package uk.crownmedia.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
import uk.crownmedia.core.model.ProviderCredentials

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvUiRegressionTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        setTelevisionMode()
        val store = AppStore(FakeSecureStore()).apply {
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
        MainActivity.storeFactory = { store }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.finish()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun homeUsesBalancedThreeByTwoCompositionAndPersistentSelection() {
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val sideNav = activity.findViewById<View>(R.id.side_nav)

        assertEquals(3, (grid.layoutManager as GridLayoutManager).spanCount)
        assertEquals(6, grid.adapter?.itemCount)
        assertTrue(activity.findViewById<View>(R.id.nav_home).isSelected)
        assertEquals(dp(120), sideNav.layoutParams.width)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.action_reload).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.action_playlist).visibility)
    }

    @Test
    fun catalogShellUsesExplicitCrossRegionFocusAndReadableTvSizing() {
        activity.findViewById<View>(R.id.nav_live).performClick()
        val grid = activity.findViewById<RecyclerView>(R.id.content_grid)
        val categories = activity.findViewById<RecyclerView>(R.id.category_list)
        val navHome = activity.findViewById<Button>(R.id.nav_home)

        assertEquals(5, (grid.layoutManager as GridLayoutManager).spanCount)
        assertEquals(R.id.nav_live, grid.nextFocusLeftId)
        assertEquals(R.id.nav_live, categories.nextFocusLeftId)
        assertEquals(R.id.content_grid, categories.nextFocusDownId)
        assertEquals(R.id.category_list, navHome.nextFocusRightId)
        val scaledDensity = activity.resources.displayMetrics.density * activity.resources.configuration.fontScale
        assertEquals(14f, navHome.textSize / scaledDensity, 0.1f)
    }

    @Test
    fun tvBrandAndCategoryGeometryFitTheirContainers() {
        val logo = activity.findViewById<ImageView>(R.id.brand_logo)
        val category = activity.layoutInflater.inflate(R.layout.item_category, FrameLayout(activity), false)

        assertEquals(dp(72), logo.layoutParams.width)
        assertEquals(dp(54), logo.layoutParams.height)
        assertEquals(dp(46), category.layoutParams.height)
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
        assertEquals(View.NO_ID, connect.nextFocusDownId)
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
