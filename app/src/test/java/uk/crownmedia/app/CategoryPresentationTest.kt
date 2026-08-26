package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uk.crownmedia.data.xtream.XtreamCategory
import uk.crownmedia.core.design.StreamAvailability

class CategoryPresentationTest {
    @Test
    fun removesProviderAllAndExactDuplicatesWhileKeepingApplicationAll() {
        val result = displayedCategoryList(
            listOf(
                XtreamCategory("0", "All Channels"),
                XtreamCategory("00", "All Streams (10221)"),
                XtreamCategory("10", "News"),
                XtreamCategory("10", "News duplicate id"),
                XtreamCategory("11", " news "),
                XtreamCategory("12", ""),
            ),
        )

        assertEquals(listOf(CATEGORY_MENU_ID, "all", "favorites", "10", "12"), result.map { it.id })
        assertEquals("Uncategorized", result.last().name)
    }

    @Test
    fun prioritizesUkThenIrelandAndPreservesProviderOrderInsideEachGroup() {
        val result = displayedCategoryList(
            listOf(
                XtreamCategory("1", "World News"),
                XtreamCategory("2", "Irish Sports"),
                XtreamCategory("3", "U.K. Entertainment"),
                XtreamCategory("4", "UK Movies"),
                XtreamCategory("5", "IE Channels"),
                XtreamCategory("6", "Documentaries"),
            ),
        )

        assertEquals(listOf("3", "4", "2", "5", "1", "6"), result.drop(3).map { it.id })
    }

    @Test
    fun shortFormsRequireWholeTokensAndDoNotMatchUnrelatedWords() {
        assertEquals(2, categoryRegionPriority("Ukraine News"))
        assertEquals(2, categoryRegionPriority("Pirate Movies"))
        assertEquals(2, categoryRegionPriority("Science"))
        assertEquals(0, categoryRegionPriority("British Entertainment"))
        assertEquals(1, categoryRegionPriority("IR Entertainment"))
        assertFalse(isProviderAllCategory(XtreamCategory("44", "Alligator TV")))
    }

    @Test
    fun liveCardsOrderHealthyThenUnknownThenFailuresWithoutDroppingAny() {
        val cards = listOf("failed", "unknown-a", "healthy", "unknown-b").map {
            CatalogCard(it, "live", it, null, "LIVE")
        }
        val statuses = mapOf(
            "failed" to StreamAvailability.Status.TEMPORARILY_FAILED,
            "healthy" to StreamAvailability.Status.HEALTHY,
        )

        val result = prioritizeLiveCards(cards) { statuses[it.id] ?: StreamAvailability.Status.UNKNOWN }

        assertEquals(listOf("healthy", "unknown-a", "unknown-b", "failed"), result.map { it.id })
    }
}
