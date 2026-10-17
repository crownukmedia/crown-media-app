package uk.crownmedia.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentPresentationTest {
    @Test
    fun readyCountsUseReadableGroupingEverywhere() {
        val state = ContentCountState.Ready(4_320)

        assertEquals("4,320", state.displayValue(Locale.US))
        assertEquals("Movies\n(4,320)", state.navigationLabel("Movies", Locale.US))
        assertEquals("Movies (4,320)", state.navigationLabel("Movies", Locale.US, television = true))
        assertEquals("4,320 movies", state.homeDescription("movies", Locale.US))
        assertEquals("4,320 movies • Details & resume", state.homeTileDescription("movies", "Details & resume", Locale.US))
    }

    @Test
    fun loadingAndFailureNeverPretendToBeZero() {
        assertEquals("", ContentCountState.Loading.homeDescription("channels", Locale.US))
        assertEquals("Live", ContentCountState.Loading.navigationLabel("Live", Locale.US))
        assertEquals("Live", ContentCountState.Loading.navigationLabel("Live", Locale.US, television = true))
        assertEquals("EPG & catch-up", ContentCountState.Loading.homeTileDescription("channels", "EPG & catch-up", Locale.US))
        assertEquals("", ContentCountState.Unavailable.homeDescription("series", Locale.US))
        assertEquals("Series", ContentCountState.Unavailable.navigationLabel("Series", Locale.US))
    }

    @Test
    fun incompleteCatalogKeepsVerifiedTotalInsteadOfPublishingPartialRows() {
        assertEquals(
            ContentCountState.Ready(480),
            cachedContentCountState(catalogComplete = true, cachedCount = 480),
        )
        assertEquals(
            ContentCountState.Loading,
            cachedContentCountState(catalogComplete = false, cachedCount = 120),
        )
        assertEquals(
            ContentCountState.Loading,
            cachedContentCountState(catalogComplete = false, cachedCount = 120),
        )
    }
}
