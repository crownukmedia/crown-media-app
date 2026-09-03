package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.crownmedia.data.xtream.XtreamCategory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CategoryAccessibilityTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun categoryDescriptionIncludesLocalizedItemCount() {
        val category = XtreamCategory("sports", "Sports")

        assertEquals("Sports, 1 item", categoryAccessibilityLabel(resources, category, 1))
        assertEquals("Sports, 124 items", categoryAccessibilityLabel(resources, category, 124))
    }

    @Test
    fun categoryDescriptionRemainsNameOnlyWhenCountIsNotDisplayed() {
        val category = XtreamCategory("all", "All")

        assertEquals("All", categoryAccessibilityLabel(resources, category, null))
    }
}
