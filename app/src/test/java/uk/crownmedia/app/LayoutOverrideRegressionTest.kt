package uk.crownmedia.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-television-xhdpi")
class LayoutOverrideRegressionTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(manager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        LayoutSelection(application).select(AppLayout.MOBILE)
        MainActivity.storeFactory = { AppStore(FakeSecureStore()) }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.finish()
        LayoutSelection(RuntimeEnvironment.getApplication()).clear()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun mobileChoiceOnPhysicalTvUsesOnlyMobileResources() {
        val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val category = activity.layoutInflater.inflate(R.layout.item_category, FrameLayout(activity), false)
        val content = activity.layoutInflater.inflate(R.layout.item_content, FrameLayout(activity), false)
        val artworkContainer = content.findViewById<ImageView>(R.id.artwork).parent as View

        assertEquals(Configuration.UI_MODE_TYPE_NORMAL, mode)
        assertEquals(dp(64), activity.findViewById<View>(R.id.side_nav).layoutParams.height)
        assertEquals(dp(152), activity.findViewById<ImageView>(R.id.login_logo).layoutParams.width)
        assertEquals(dp(48), category.layoutParams.height)
        assertEquals(dp(104), artworkContainer.layoutParams.height)
        assertEquals(1, (activity.findViewById<RecyclerView>(R.id.content_grid).layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private class FakeSecureStore : CrownSecureStore {
        override fun getString(key: String, fallback: String?): String? = fallback
        override fun putString(key: String, value: String?) = Unit
        override fun getStringSet(key: String): Set<String> = emptySet()
        override fun putStringSet(key: String, value: Set<String>) = Unit
    }
}
