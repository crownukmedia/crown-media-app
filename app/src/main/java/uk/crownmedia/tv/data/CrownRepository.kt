package uk.crownmedia.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uk.crownmedia.tv.model.ContentCategory
import uk.crownmedia.tv.model.DashboardSnapshot
import uk.crownmedia.tv.model.EpgEntry
import uk.crownmedia.tv.model.LiveStream
import uk.crownmedia.tv.model.ProviderCredentials
import uk.crownmedia.tv.model.ProviderSession
import uk.crownmedia.tv.model.SeriesDetail
import uk.crownmedia.tv.model.SeriesItem
import uk.crownmedia.tv.model.VodDetail
import uk.crownmedia.tv.model.VodStream

class CrownRepository(
    private val client: XtreamClient = XtreamClient(),
) {
    suspend fun authenticate(username: String, password: String): ProviderSession =
        client.authenticate(ProviderCredentials(username.trim(), password.trim()))

    suspend fun loadDashboard(session: ProviderSession): DashboardSnapshot = coroutineScope {
        val liveCategories = async { client.liveCategories(session.credentials) }
        val movieCategories = async { client.vodCategories(session.credentials) }
        val seriesCategories = async { client.seriesCategories(session.credentials) }

        val resolvedLiveCategories = liveCategories.await()
        val resolvedMovieCategories = movieCategories.await()
        val resolvedSeriesCategories = seriesCategories.await()

        val featuredLive = async {
            resolvedLiveCategories.smartDefault("sports")?.id?.let {
                client.liveStreams(session.credentials, it).take(12)
            }.orEmpty()
        }
        val featuredMovies = async {
            resolvedMovieCategories.smartDefault("just released")?.id?.let {
                client.vodStreams(session.credentials, it).take(12)
            }.orEmpty()
        }
        val featuredSeries = async {
            client.series(session.credentials).take(12)
        }

        DashboardSnapshot(
            liveCategories = resolvedLiveCategories,
            movieCategories = resolvedMovieCategories,
            seriesCategories = resolvedSeriesCategories,
            featuredLive = featuredLive.await(),
            featuredMovies = featuredMovies.await(),
            featuredSeries = featuredSeries.await(),
        )
    }

    suspend fun loadLiveStreams(session: ProviderSession, categoryId: String): List<LiveStream> =
        client.liveStreams(session.credentials, categoryId)

    suspend fun loadVodStreams(session: ProviderSession, categoryId: String): List<VodStream> =
        client.vodStreams(session.credentials, categoryId)

    suspend fun loadVodDetail(session: ProviderSession, streamId: String): VodDetail =
        client.vodDetail(session.credentials, streamId)

    suspend fun loadSeries(session: ProviderSession, categoryId: String): List<SeriesItem> =
        client.series(session.credentials, categoryId)

    suspend fun loadSeriesDetail(session: ProviderSession, seriesId: String): SeriesDetail =
        client.seriesDetail(session.credentials, seriesId)

    suspend fun loadEpg(session: ProviderSession, streamId: String): List<EpgEntry> =
        client.shortEpg(session.credentials, streamId)

    fun buildLiveUrl(session: ProviderSession, streamId: String): String =
        client.buildLiveUrl(session, streamId)

    fun buildMovieUrl(session: ProviderSession, streamId: String, extension: String): String =
        client.buildMovieUrl(session, streamId, extension)

    fun buildEpisodeUrl(session: ProviderSession, episodeId: String, extension: String): String =
        client.buildEpisodeUrl(session, episodeId, extension)

    fun buildCatchupUrl(session: ProviderSession, streamId: String, entry: EpgEntry): String? =
        client.buildCatchupUrl(session, streamId, entry)
}

private fun List<ContentCategory>.smartDefault(keyword: String): ContentCategory? =
    firstOrNull { it.name.contains(keyword, ignoreCase = true) } ?: firstOrNull()
