package uk.crownmedia.app

import uk.crownmedia.data.xtream.XtreamEpisode
import uk.crownmedia.data.xtream.XtreamSeriesDetails

/** Provider-order-safe episode sequence used by the internal player's continuation queue. */
internal fun orderedSeriesEpisodes(details: XtreamSeriesDetails): List<XtreamEpisode> =
    details.episodes.toSortedMap().flatMap { (_, episodes) ->
        episodes.sortedWith(compareBy<XtreamEpisode> { it.episodeNumber }.thenBy { it.id })
    }.filter { it.id.isNotBlank() }.distinctBy { it.id }
