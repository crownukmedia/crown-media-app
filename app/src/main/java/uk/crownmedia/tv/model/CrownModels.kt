package uk.crownmedia.tv.model

import uk.crownmedia.tv.config.CrownConfig

enum class AppSection {
    HOME,
    LIVE,
    MOVIES,
    SERIES,
    FAVORITES,
    SEARCH,
    SETTINGS,
}

enum class ContentType {
    LIVE,
    MOVIE,
    SERIES,
    EPISODE,
}

data class ProviderCredentials(
    val username: String,
    val password: String,
)

data class ProviderSession(
    val credentials: ProviderCredentials,
    val status: String,
    val expiryEpochSeconds: Long?,
    val maxConnections: Int,
    val allowedFormats: List<String>,
) {
    val preferredLiveFormat: String
        get() = when {
            allowedFormats.contains("m3u8") -> "m3u8"
            allowedFormats.isNotEmpty() -> allowedFormats.first()
            else -> CrownConfig.defaultLiveFormat
        }
}

data class ContentCategory(
    val id: String,
    val name: String,
)

data class LiveStream(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val epgChannelId: String?,
    val categoryId: String,
    val hasCatchup: Boolean,
    val catchupWindowHours: Int,
)

data class VodStream(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val rating: String?,
    val categoryId: String,
    val extension: String,
)

data class VodDetail(
    val stream: VodStream,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val durationLabel: String?,
    val releaseDate: String?,
    val backdropUrl: String?,
)

data class SeriesItem(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val rating: String?,
    val categoryId: String,
)

data class SeriesEpisode(
    val id: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val plot: String?,
    val posterUrl: String?,
    val durationLabel: String?,
    val extension: String,
)

data class SeriesDetail(
    val item: SeriesItem,
    val cast: String?,
    val genre: String?,
    val episodesBySeason: Map<Int, List<SeriesEpisode>>,
)

data class EpgEntry(
    val id: String,
    val title: String,
    val description: String?,
    val start: String,
    val end: String,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

data class DashboardSnapshot(
    val liveCategories: List<ContentCategory>,
    val movieCategories: List<ContentCategory>,
    val seriesCategories: List<ContentCategory>,
    val featuredLive: List<LiveStream>,
    val featuredMovies: List<VodStream>,
    val featuredSeries: List<SeriesItem>,
)

data class PlayerRequest(
    val title: String,
    val subtitle: String,
    val url: String,
    val contentType: ContentType,
)

data class SearchResult(
    val key: String,
    val label: String,
    val detail: String,
    val contentType: ContentType,
    val destinationSection: AppSection,
    val categoryId: String? = null,
)

fun LiveStream.favoriteKey(): String = "live:$id"
fun VodStream.favoriteKey(): String = "movie:$id"
fun SeriesItem.favoriteKey(): String = "series:$id"
fun SeriesEpisode.favoriteKey(): String = "episode:$id"
