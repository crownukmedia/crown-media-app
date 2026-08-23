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
