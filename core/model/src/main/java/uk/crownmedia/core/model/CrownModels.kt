package uk.crownmedia.core.model

import java.time.Instant

@JvmInline value class PlaylistId(val value: String)
@JvmInline value class CategoryId(val value: String)
@JvmInline value class ContentId(val value: String)

enum class AccountStatus { ACTIVE, EXPIRED, DISABLED, UNKNOWN }
enum class PlaylistState { UNCONFIGURED, VALIDATING, ACTIVE, REFRESHING, STALE_CACHE, EXPIRED, DISABLED, INVALID_CREDENTIALS, SERVER_UNREACHABLE, INCOMPATIBLE_SERVER }
enum class ContentKind { LIVE, MOVIE, SERIES, EPISODE, CATCH_UP }
enum class PlaybackMode { LIVE, CATCH_UP, VOD }
enum class PlayerPreference { INTERNAL, VLC, MX_PLAYER, SYSTEM_CHOOSER }
enum class SyncPhase { IDLE, QUEUED, FETCHING, PARSING, COMMITTING, SUCCESS, FAILED }
enum class SortOrder { PROVIDER, NAME_ASC, NAME_DESC, NEWEST, RATING, RECENTLY_WATCHED }

data class ProviderCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

data class PlaylistProfile(
    val id: PlaylistId,
    val displayName: String,
    val normalizedServerUrl: String,
    val activationMethod: ActivationMethod,
    val deviceKey: String,
    val createdAt: Instant,
    val lastSelectedAt: Instant,
)

enum class ActivationMethod { MANUAL, DEVICE_CODE }

data class AccountSnapshot(
    val playlistId: PlaylistId,
    val status: AccountStatus,
    val expiry: Instant?,
    val playlistExpiry: Instant?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val serverTimezone: String?,
    val allowedOutputFormats: Set<String>,
    val lastValidatedAt: Instant,
)

data class ContentCategory(
    val playlistId: PlaylistId,
    val id: CategoryId,
    val kind: ContentKind,
    val name: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
    val locked: Boolean = false,
)

data class LiveChannel(
    val playlistId: PlaylistId,
    val id: ContentId,
    val categoryId: CategoryId,
    val name: String,
    val normalizedName: String,
    val channelNumber: Int?,
    val logoUrl: String?,
    val epgChannelId: String?,
    val catchUpAvailable: Boolean,
    val catchUpDays: Int,
    val streamExtension: String,
)

data class Programme(
    val playlistId: PlaylistId,
    val channelId: ContentId,
    val id: String,
    val title: String,
    val description: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val catchUpAvailable: Boolean,
)

data class Movie(
    val playlistId: PlaylistId,
    val id: ContentId,
    val categoryId: CategoryId,
    val title: String,
    val normalizedTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val year: Int?,
    val durationSeconds: Long?,
    val rating: Double?,
    val genres: List<String>,
    val cast: List<String>,
    val trailerId: String?,
    val containerExtension: String,
    val addedAt: Instant?,
)

data class Series(
    val playlistId: PlaylistId,
    val id: ContentId,
    val categoryId: CategoryId,
    val title: String,
    val normalizedTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val cast: List<String>,
    val trailerId: String?,
    val addedAt: Instant?,
)

data class Season(
    val playlistId: PlaylistId,
    val seriesId: ContentId,
    val number: Int,
    val title: String,
    val artworkUrl: String?,
)

data class Episode(
    val playlistId: PlaylistId,
    val seriesId: ContentId,
    val id: ContentId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val description: String?,
    val durationSeconds: Long?,
    val artworkUrl: String?,
    val containerExtension: String,
)

data class Favorite(
    val playlistId: PlaylistId,
    val kind: ContentKind,
    val contentId: ContentId,
    val createdAt: Instant,
)

data class PlaybackProgress(
    val playlistId: PlaylistId,
    val kind: ContentKind,
    val contentId: ContentId,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Instant,
)

data class PlaybackRequest(
    val playlistId: PlaylistId,
    val kind: ContentKind,
    val sourceId: ContentId,
    val mode: PlaybackMode,
    val startPositionMs: Long = 0,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val playerPreference: PlayerPreference = PlayerPreference.INTERNAL,
    val catchUpStart: Instant? = null,
    val catchUpDurationSeconds: Long? = null,
)

data class PlayerSettings(
    val playerPreference: PlayerPreference = PlayerPreference.INTERNAL,
    val bufferProfile: BufferProfile = BufferProfile.NORMAL,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val autoPlayNextEpisode: Boolean = true,
)

enum class BufferProfile { LOW_LATENCY, NORMAL, RESILIENT }

data class ParentalSettings(
    val enabled: Boolean = false,
    val lockedCategoryIds: Set<CategoryId> = emptySet(),
    val hideLockedSearchResults: Boolean = true,
    val unlockDurationMinutes: Int = 15,
)

data class SyncStatus(
    val family: SyncFamily,
    val phase: SyncPhase,
    val lastSuccessAt: Instant?,
    val error: CrownError? = null,
)

enum class SyncFamily { ACCOUNT, LIVE, MOVIES, SERIES, EPG, METADATA }

sealed class CrownError(open val diagnosticCode: String, override val message: String) : Exception(message) {
    data class InvalidCredentials(override val message: String = "The username or password is not accepted") : CrownError("INVALID_CREDENTIALS", message)
    data class AccountExpired(override val message: String = "This account has expired") : CrownError("ACCOUNT_EXPIRED", message)
    data class AccountDisabled(override val message: String = "This account is disabled") : CrownError("ACCOUNT_DISABLED", message)
    data class ConnectionLimit(override val message: String = "The account connection limit has been reached") : CrownError("CONNECTION_LIMIT", message)
    data class ServerUnreachable(override val message: String = "The server cannot be reached") : CrownError("SERVER_UNREACHABLE", message)
    data class IncompatibleResponse(override val message: String = "The server response is not compatible") : CrownError("INCOMPATIBLE_RESPONSE", message)
    data class CatalogFailure(val family: SyncFamily, override val message: String) : CrownError("${family.name}_REFRESH_FAILED", message)
    data class PlaybackFailure(override val diagnosticCode: String, override val message: String) : CrownError(diagnosticCode, message)
    data class Unknown(override val message: String) : CrownError("UNKNOWN", message)
}
