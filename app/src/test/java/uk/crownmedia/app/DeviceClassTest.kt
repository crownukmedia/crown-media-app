package uk.crownmedia.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceClassTest {
    private lateinit var selection: LayoutSelection

    @Before
    fun setUp() {
        selection = LayoutSelection(RuntimeEnvironment.getApplication())
        selection.clear()
    }

    @After
    fun tearDown() = selection.clear()

    @Test
    fun startupLayoutKeepsMobileAndTelevisionResourcesSeparate() {
        assertEquals(R.layout.activity_main, DeviceClass.PHONE.defaultLayout().startupLayoutResource())
        assertEquals(R.layout.activity_main, DeviceClass.TABLET.defaultLayout().startupLayoutResource())
        assertEquals(R.layout.activity_main_television, DeviceClass.TELEVISION.defaultLayout().startupLayoutResource())
    }

    @Test
    fun explicitUserChoiceOverridesDetectionAndPersists() {
        assertFalse(selection.hasUserChoice)
        assertEquals(AppLayout.TELEVISION, selection.resolve(DeviceClass.TELEVISION))

        selection.select(AppLayout.MOBILE)

        assertTrue(selection.hasUserChoice)
        assertEquals(AppLayout.MOBILE, LayoutSelection(RuntimeEnvironment.getApplication()).resolve(DeviceClass.TELEVISION))
    }
}
