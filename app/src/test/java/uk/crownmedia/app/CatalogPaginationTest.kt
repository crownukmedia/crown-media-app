package uk.crownmedia.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.crownmedia.core.database.CrownDatabase
import uk.crownmedia.data.xtream.XtreamCategory
import uk.crownmedia.data.xtream.XtreamItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogPaginationTest {
    private lateinit var database: CrownDatabase
    private lateinit var cache: CatalogCache

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CrownDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = CatalogCache(database.catalogDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun readsLargeCatalogInStableSixtyItemWindows() = runBlocking {
        cache.saveItems("playlist", "movie", null, items(1_000))

        val first = cache.itemPage("playlist", "movie", null, 60, 0)
        val second = cache.itemPage("playlist", "movie", null, 60, 60)

        assertEquals(60, first.size)
        assertEquals("0", first.first().id)
        assertEquals("59", first.last().id)
        assertEquals("60", second.first().id)
        assertEquals(1_000, cache.itemCount("playlist", "movie", null))
    }

    @Test
    fun failedEmptyRefreshKeepsLastKnownGoodCatalog() = runBlocking {
        cache.saveItems("playlist", "series", null, items(120))

        cache.finishItemRefresh("playlist", "series", null, System.currentTimeMillis(), received = 0)

        assertEquals(120, cache.itemCount("playlist", "series", null))
        assertTrue(cache.itemPage("playlist", "series", null, 60, 0).isNotEmpty())
    }

    @Test
    fun nameSortingIsGlobalAcrossPageBoundaries() = runBlocking {
        val values = (0 until 130).map { index ->
            items(1).first().copy(id = index.toString(), name = "Title ${(129 - index).toString().padStart(3, '0')}", providerOrder = index)
        }
        cache.saveItems("playlist", "movie", null, values)

        val first = cache.itemPage("playlist", "movie", null, 60, 0, "asc")
        val second = cache.itemPage("playlist", "movie", null, 60, 60, "asc")

        assertTrue(first.last().name <= second.first().name)
    }

    @Test
    fun tokenSearchFindsWordPrefixesWithoutScanningArbitrarySubstrings() = runBlocking {
        cache.saveItems("playlist", "movie", null, listOf(
            items(1).first().copy(id = "a", name = "The Crown Story"),
            items(1).first().copy(id = "b", name = "Unrelated Film"),
        ))

        val results = cache.search("playlist", "crow sto")

        assertEquals(listOf("a"), results.map { it.second.id })
    }

    @Test
    fun scopedSearchNeverReturnsAnotherContentKind() = runBlocking {
        cache.saveItems("playlist", "live", null, listOf(items(1).first().copy(id = "live", name = "Sky Sports")))
        cache.saveItems("playlist", "movie", null, listOf(items(1).first().copy(id = "movie", name = "Sports Story")))
        cache.saveItems("playlist", "series", null, listOf(items(1).first().copy(id = "series", name = "Sports Network")))

        val live = cache.search("playlist", "SPORT", kind = "live")
        val movies = cache.search("playlist", "sport", kind = "movie")

        assertEquals(listOf("live"), live.map { it.second.id })
        assertEquals(listOf("movie"), movies.map { it.second.id })
        assertTrue(live.all { it.first == "live" })
        assertTrue(movies.all { it.first == "movie" })
    }

    @Test
    fun providerCategoriesRemainAvailableAlongsideWarmCatalogItems() = runBlocking {
        val categories = listOf(
            XtreamCategory("10", "Just Released"),
            XtreamCategory("20", "Drama"),
        )

        cache.saveCategories("playlist", "movie", categories)
        cache.saveItems("playlist", "movie", null, items(20))

        assertEquals(categories, cache.categories("playlist", "movie"))
    }

    @Test
    fun refreshKeepsExplicitProviderOrderAndAdultClassification() = runBlocking {
        cache.saveItemBatch(
            "playlist",
            "live",
            refreshMarker = 10L,
            values = listOf(
                items(1).first().copy(id = "later", providerOrder = 40),
                items(1).first().copy(id = "adult", providerOrder = 20, isAdult = true),
            ),
        )

        val page = cache.itemPage("playlist", "live", null, 60, 0)

        assertEquals(listOf("adult", "later"), page.map { it.id })
        assertTrue(page.first().isAdult)
    }

    @Test
    fun accessibleCountsMatchRenderedRowsForEveryKindAndCategory() = runBlocking {
        listOf("live", "movie", "series").forEach { kind ->
            cache.saveItems(
                "playlist",
                kind,
                null,
                listOf(
                    items(1).first().copy(id = "$kind-1", categoryId = "news"),
                    items(1).first().copy(id = "$kind-2", categoryId = "news", isAdult = true),
                    items(1).first().copy(id = "$kind-3", categoryId = "sport"),
                    // A repeated server id replaces the same catalog identity; it is never
                    // double-counted even if a provider response repeats it.
                    items(1).first().copy(id = "$kind-3", categoryId = "sport"),
                ),
            )

            assertEquals(2, cache.accessibleCount("playlist", kind, includeAdult = false))
            assertEquals(3, cache.accessibleCount("playlist", kind, includeAdult = true))
            assertEquals(
                mapOf("news" to 1, "sport" to 1),
                cache.accessibleCategoryCounts("playlist", kind, includeAdult = false),
            )
            assertEquals(
                mapOf("news" to 2, "sport" to 1),
                cache.accessibleCategoryCounts("playlist", kind, includeAdult = true),
            )
        }
    }

    private fun items(count: Int) = List(count) { index ->
        XtreamItem(
            id = index.toString(),
            categoryId = (index % 5).toString(),
            name = "Title $index",
            imageUrl = "https://img/$index.jpg",
            rating = null,
            addedEpochSeconds = null,
            extension = "mp4",
            epgChannelId = null,
            catchUp = false,
            catchUpDays = 0,
        )
    }
}
