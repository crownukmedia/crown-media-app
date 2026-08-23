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
        assertEquals("4,320 movies", state.homeDescription("movies", Locale.US))
    }

    @Test
    fun loadingAndFailureNeverPretendToBeZero() {
        assertEquals("Loading channels count…", ContentCountState.Loading.homeDescription("channels", Locale.US))
        assertEquals("Live\n(…)", ContentCountState.Loading.navigationLabel("Live", Locale.US))
        assertEquals("series count unavailable", ContentCountState.Unavailable.homeDescription("series", Locale.US))
        assertEquals("Series\n(—)", ContentCountState.Unavailable.navigationLabel("Series", Locale.US))
    }
}
