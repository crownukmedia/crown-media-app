package uk.crownmedia.domain

import kotlinx.coroutines.flow.Flow
import uk.crownmedia.core.model.AccountSnapshot
import uk.crownmedia.core.model.ContentCategory
import uk.crownmedia.core.model.ContentId
import uk.crownmedia.core.model.ContentKind
import uk.crownmedia.core.model.Episode
import uk.crownmedia.core.model.Favorite
import uk.crownmedia.core.model.LiveChannel
import uk.crownmedia.core.model.Movie
import uk.crownmedia.core.model.PlaybackProgress
import uk.crownmedia.core.model.PlaybackRequest
import uk.crownmedia.core.model.PlaylistId
import uk.crownmedia.core.model.PlaylistProfile
import uk.crownmedia.core.model.Programme
import uk.crownmedia.core.model.ProviderCredentials
import uk.crownmedia.core.model.Season
import uk.crownmedia.core.model.Series
import uk.crownmedia.core.model.SortOrder
import uk.crownmedia.core.model.SyncStatus
import java.time.Instant

interface AccountRepository {
    fun observeAccount(playlistId: PlaylistId): Flow<AccountSnapshot?>
    suspend fun validate(credentials: ProviderCredentials): AccountSnapshot
    suspend fun refresh(playlistId: PlaylistId): Result<AccountSnapshot>
}

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistProfile>>
    fun observeSelectedPlaylist(): Flow<PlaylistProfile?>
    suspend fun add(displayName: String, credentials: ProviderCredentials): Result<PlaylistProfile>
    suspend fun update(profile: PlaylistProfile, credentials: ProviderCredentials?): Result<PlaylistProfile>
    suspend fun select(id: PlaylistId)
    suspend fun remove(id: PlaylistId)
    suspend fun credentials(id: PlaylistId): ProviderCredentials
}

interface LiveRepository {
    fun observeCategories(playlistId: PlaylistId): Flow<List<ContentCategory>>
    fun observeChannels(playlistId: PlaylistId, categoryId: String?, query: String? = null): Flow<List<LiveChannel>>
    suspend fun refresh(playlistId: PlaylistId): Result<Unit>
}

interface EpgRepository {
    fun observeNowNext(playlistId: PlaylistId, channelId: ContentId): Flow<List<Programme>>
    fun observeProgrammes(playlistId: PlaylistId, channelId: ContentId, from: Instant, to: Instant): Flow<List<Programme>>
    suspend fun refresh(playlistId: PlaylistId, from: Instant, to: Instant): Result<Unit>
}

interface MovieRepository {
    fun observeCategories(playlistId: PlaylistId): Flow<List<ContentCategory>>
    fun observeMovies(playlistId: PlaylistId, categoryId: String?, query: String?, order: SortOrder): Flow<List<Movie>>
    fun observeMovie(playlistId: PlaylistId, id: ContentId): Flow<Movie?>
    suspend fun refresh(playlistId: PlaylistId): Result<Unit>
    suspend fun refreshDetails(playlistId: PlaylistId, id: ContentId): Result<Movie>
}

interface SeriesRepository {
    fun observeCategories(playlistId: PlaylistId): Flow<List<ContentCategory>>
    fun observeSeries(playlistId: PlaylistId, categoryId: String?, query: String?, order: SortOrder): Flow<List<Series>>
    fun observeSeriesItem(playlistId: PlaylistId, id: ContentId): Flow<Series?>
    fun observeSeasons(playlistId: PlaylistId, seriesId: ContentId): Flow<List<Season>>
    fun observeEpisodes(playlistId: PlaylistId, seriesId: ContentId, season: Int): Flow<List<Episode>>
    suspend fun refresh(playlistId: PlaylistId): Result<Unit>
    suspend fun refreshDetails(playlistId: PlaylistId, id: ContentId): Result<Unit>
}

interface PersonalizationRepository {
    fun observeFavorites(playlistId: PlaylistId, kind: ContentKind? = null): Flow<List<Favorite>>
    suspend fun toggleFavorite(playlistId: PlaylistId, kind: ContentKind, contentId: ContentId)
    fun observeProgress(playlistId: PlaylistId, kind: ContentKind, contentId: ContentId): Flow<PlaybackProgress?>
    fun observeContinueWatching(playlistId: PlaylistId): Flow<List<PlaybackProgress>>
    suspend fun saveProgress(progress: PlaybackProgress)
}

interface PlaybackResolver {
    suspend fun resolve(request: PlaybackRequest): ResolvedPlayback
}

data class ResolvedPlayback(
    val uri: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val title: String,
)

interface SyncRepository {
    fun observeStatuses(playlistId: PlaylistId): Flow<List<SyncStatus>>
    suspend fun refreshAll(playlistId: PlaylistId): Result<Unit>
}
