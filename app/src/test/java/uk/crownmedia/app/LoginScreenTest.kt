package uk.crownmedia.app

import android.view.View
import android.widget.ImageView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginScreenTest {
    private var activity: MainActivity? = null

    @Before
    fun setUp() {
        MainActivity.storeFactory = { AppStore(FakeSecureStore()) }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity?.finish()
        MainActivity.storeFactory = ::AppStore
    }

    @Test
    fun freshLaunchShowsStandaloneLoginWithoutAppNavigation() {
        val screen = requireNotNull(activity)
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.login_panel).visibility)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.top_bar).visibility)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.side_nav).visibility)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.action_more).visibility)
        assertTrue(screen.findViewById<View>(R.id.connect_button).isShown)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.qr_button).visibility)
    }

    @Test
    fun mobileLoginLogoUsesContainedCenteredArtworkWithSafePadding() {
        val screen = requireNotNull(activity)
        val logo = screen.findViewById<ImageView>(R.id.login_logo)
        val expectedPadding = (8 * screen.resources.displayMetrics.density).toInt()

        assertEquals(ImageView.ScaleType.FIT_CENTER, logo.scaleType)
        assertEquals(expectedPadding, logo.paddingLeft)
        assertEquals(expectedPadding, logo.paddingTop)
        assertEquals(expectedPadding, logo.paddingRight)
        assertEquals(expectedPadding, logo.paddingBottom)
    }

    @Test
    fun loginDefaultsToPremiumAndDoesNotRequirePlaylistName() {
        val screen = requireNotNull(activity)
        val service = screen.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.service_dropdown)
        assertEquals("Crown Premium", service.text.toString())
        assertTrue(screen.findViewById<View>(R.id.playlist_name).isEnabled)
        assertTrue(screen.findViewById<View>(R.id.connect_button).isEnabled)
    }

    @Test
    fun everyCrownServiceUsesTheSharedLoginFlowWithoutReusingTypedCredentials() {
        val screen = requireNotNull(activity)
        val service = screen.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.service_dropdown)
        val username = screen.findViewById<android.widget.EditText>(R.id.username)
        username.setText("premium-user")

        service.setText(CrownService.PRO.displayName, false)
        service.onItemClickListener?.onItemClick(null, service, 1, 1L)

        assertEquals("", username.text.toString())
        assertTrue(screen.findViewById<View>(R.id.connect_button).isEnabled)

        service.setText(CrownService.EIGHT_K.displayName, false)
        service.onItemClickListener?.onItemClick(null, service, 2, 2L)
        assertTrue(screen.findViewById<View>(R.id.connect_button).isEnabled)
    }

    @Test
    fun qrConnectionCodeRemainsPresentButHidden() {
        val button = requireNotNull(activity).findViewById<android.widget.Button>(R.id.qr_button)
        assertTrue(button.text.contains("Coming soon"))
        assertTrue(!button.isEnabled)
        assertEquals(View.GONE, button.visibility)
    }

    @Test
    fun connectingToPremiumDoesNotShowAnHttpWarningDialog() {
        val screen = requireNotNull(activity)
        screen.findViewById<android.widget.EditText>(R.id.username).setText("test-user")
        screen.findViewById<android.widget.EditText>(R.id.password).setText("test-password")

        screen.findViewById<View>(R.id.connect_button).performClick()

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
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
