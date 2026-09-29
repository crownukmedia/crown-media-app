package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uk.crownmedia.data.xtream.XtreamCategory

class CategoryPresentationTest {
    @Test
    fun removesProviderAllAndDuplicateIdsWhileKeepingDistinctProviderBouquets() {
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

        assertEquals(listOf("all", "favorites", "10", "11", "12"), result.map { it.id })
        assertEquals("Uncategorized", result.last().name)
    }

    @Test
    fun preservesExactProviderCategoryOrderWithoutRegionalReordering() {
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

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), result.drop(2).map { it.id })
    }

    @Test
    fun crown8kDolbyAudioAndNowTvUhdFoldersRemainVisibleWhenProviderReturnsThem() {
        val result = displayedCategoryList(
            listOf(
                XtreamCategory("1964", "UK| NOW TV SPORT ᴴᴰ/ᴿᴬᵂ"),
                XtreamCategory("1965", "UK| NOW TV SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
                XtreamCategory("1726", "UK| TNT SPORT ᴿᴬᵂ ⱽᴵᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ"),
                XtreamCategory("1731", "UK| SPORT ᴿᴬᵂ ⱽᴵᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ"),
            ),
        )

        assertEquals(listOf("1964", "1965", "1726", "1731"), result.drop(2).map { it.id })
        assertFalse(isProviderAllCategory(XtreamCategory("44", "Alligator TV")))
    }

    @Test
    fun liveCardsKeepProviderOrderWithoutHealthReordering() {
        val cards = listOf("failed", "unknown-a", "healthy", "unknown-b").map {
            CatalogCard(it, "live", it, null, "LIVE")
        }

        assertEquals(cards, orderedCatalogCards(cards, "provider"))
    }

    @Test
    fun emptyCategoryFilteringPreservesProviderOrder() {
        val categories = listOf(
            XtreamCategory("first", "First"),
            XtreamCategory("empty", "Empty"),
            XtreamCategory("last", "Last"),
        )

        assertEquals(
            listOf("first", "last"),
            availableCategoriesInProviderOrder(categories, setOf("first", "last"), catalogComplete = true).map { it.id },
        )
        assertEquals(
            categories,
            availableCategoriesInProviderOrder(categories, emptySet(), catalogComplete = false),
        )
    }
}
