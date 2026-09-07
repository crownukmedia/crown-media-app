package uk.crownmedia.app

import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun bundledTilesUseControlledSectionAccentPalette() {
        assertEquals(R.color.crown_accent_live, card(R.drawable.home_live_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_movies, card(R.drawable.home_movies_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_series, card(R.drawable.home_series_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_guide, card(R.drawable.home_epg_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_favourites, card(R.drawable.home_favorites_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_catch_up, card(R.drawable.home_catch_up_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_manage, card(R.drawable.home_account_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_manage, card(R.drawable.home_reload_icon).tileAccentColorRes())
        assertEquals(R.color.crown_accent_manage, card(R.drawable.home_playlist_icon).tileAccentColorRes())
        assertNull(CatalogCard("remote", "movie", "Remote", "https://images.example/poster.jpg", "").tileAccentColorRes())
    }

    @Test
    fun allTileAccentBadgesMeetSmallTextContrastTarget() {
        val context = RuntimeEnvironment.getApplication()
        val darkText = ContextCompat.getColor(context, R.color.crown_background)
        val accents = listOf(
            R.color.crown_accent_live,
            R.color.crown_accent_movies,
            R.color.crown_accent_series,
            R.color.crown_accent_guide,
            R.color.crown_accent_favourites,
            R.color.crown_accent_catch_up,
            R.color.crown_accent_manage,
        )

        accents.forEach { accent ->
            val contrast = ColorUtils.calculateContrast(ContextCompat.getColor(context, accent), darkText)
            org.junit.Assert.assertTrue("Accent $accent contrast was $contrast", contrast >= 4.5)
        }
    }

    private fun card(artwork: Int) = CatalogCard("tile", "home", "Tile", null, "", localArtwork = artwork)
}
