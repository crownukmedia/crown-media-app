package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.crownmedia.data.xtream.XtreamEpisode
import uk.crownmedia.data.xtream.XtreamSeriesDetails

class SeriesPlaybackTest {
    @Test
    fun ordersEpisodesWithinSeasonThenContinuesIntoNextSeasonWithoutDuplicates() {
        val details = details(
            mapOf(
                2 to listOf(episode("s2e2", 2, 2), episode("s2e1", 2, 1)),
                1 to listOf(episode("s1e2", 1, 2), episode("s1e1", 1, 1), episode("s1e1", 1, 1)),
            ),
        )

        assertEquals(
            listOf("s1e1", "s1e2", "s2e1", "s2e2"),
            orderedSeriesEpisodes(details).map { it.id },
        )
    }

    private fun details(episodes: Map<Int, List<XtreamEpisode>>) = XtreamSeriesDetails(
        name = "Series",
        plot = null,
        cover = null,
        backdrop = null,
        cast = null,
        genre = null,
        rating = null,
        trailer = null,
        episodes = episodes,
    )

    private fun episode(id: String, season: Int, number: Int) = XtreamEpisode(
        id = id,
        season = season,
        episodeNumber = number,
        title = id,
        extension = "mkv",
        imageUrl = null,
        plot = null,
        duration = null,
    )
}
