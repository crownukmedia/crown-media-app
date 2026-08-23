package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogArtworkTest {
    @Test
    fun channelImageHasPriorityOverLocalAndBrandArtwork() {
        val card = CatalogCard(
            id = "1",
            kind = "live",
            title = "Channel",
            imageUrl = "https://images.example/channel.png",
            meta = "",
            localArtwork = R.drawable.ic_crown_placeholder,
        )

        assertEquals("https://images.example/channel.png", card.preferredArtworkSource())
    }

    @Test
    fun invalidOrMissingChannelImageFallsBackToBrandLogo() {
        val malformed = CatalogCard("1", "live", "Channel", "not a URL", "")
        val missing = CatalogCard("2", "live", "Channel", "  ", "")

        assertEquals(R.drawable.crown_media_logo_header, malformed.preferredArtworkSource())
        assertEquals(R.drawable.crown_media_logo_header, missing.preferredArtworkSource())
    }
}
