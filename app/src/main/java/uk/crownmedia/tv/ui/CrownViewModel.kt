package uk.crownmedia.tv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.crownmedia.tv.config.CrownConfig
import uk.crownmedia.tv.data.CrownPreferences
import uk.crownmedia.tv.data.CrownRepository
import uk.crownmedia.tv.model.AppSection
import uk.crownmedia.tv.model.ContentCategory
import uk.crownmedia.tv.model.ContentType
import uk.crownmedia.tv.model.DashboardSnapshot
import uk.crownmedia.tv.model.EpgEntry
import uk.crownmedia.tv.model.LiveStream
import uk.crownmedia.tv.model.PlayerRequest
import uk.crownmedia.tv.model.ProviderSession
import uk.crownmedia.tv.model.SearchResult
import uk.crownmedia.tv.model.SeriesDetail
import uk.crownmedia.tv.model.SeriesEpisode
import uk.crownmedia.tv.model.SeriesItem
import uk.crownmedia.tv.model.VodDetail
import uk.crownmedia.tv.model.VodStream
import uk.crownmedia.tv.model.favoriteKey

data class CrownUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val session: ProviderSession? = null,
    val dashboard: DashboardSnapshot? = null,
    val selectedSection: AppSection = AppSection.HOME,
    val liveCategoryId: String? = null,
    val movieCategoryId: String? = null,
    val seriesCategoryId: String? = null,
    val liveStreams: List<LiveStream> = emptyList(),
    val movies: List<VodStream> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val selectedLive: LiveStream? = null,
    val selectedMovieDetail: VodDetail? = null,
    val selectedSeriesDetail: SeriesDetail? = null,
    val epg: List<EpgEntry> = emptyList(),
    val playerRequest: PlayerRequest? = null,
    val favorites: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val lastUsername: String = "",
    val hasPin: Boolean = false,
    val pinLocked: Boolean = false,
    val pinError: String? = null,
)

class CrownViewModel(
    private val repository: CrownRepository,
    private val preferences: CrownPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CrownUiState(
            favorites = preferences.favoriteKeys(),
            lastUsername = preferences.lastUsername(),
            hasPin = preferences.hasPin(),
            pinLocked = preferences.hasPin(),
        ),
    )
    val uiState: StateFlow<CrownUiState> = _uiState

    fun login(username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val session = repository.authenticate(username, password)
                val dashboard = repository.loadDashboard(session)
                val liveCategoryId = dashboard.liveCategories.smartDefaultId("sports")
                val movieCategoryId = dashboard.movieCategories.smartDefaultId("just released")
                val seriesCategoryId = dashboard.seriesCategories.smartDefaultId("netflix")
                val liveStreams = liveCategoryId?.let { repository.loadLiveStreams(session, it).take(40) }.orEmpty()
                val movies = movieCategoryId?.let { repository.loadVodStreams(session, it).take(40) }.orEmpty()
                val series = seriesCategoryId?.let { repository.loadSeries(session, it).take(40) }.orEmpty()
                LoginPayload(session, dashboard, liveCategoryId, movieCategoryId, seriesCategoryId, liveStreams, movies, series)
            }.onSuccess { payload ->
                preferences.saveLastUsername(username.trim())
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        session = payload.session,
                        dashboard = payload.dashboard,
                        liveCategoryId = payload.liveCategoryId,
                        movieCategoryId = payload.movieCategoryId,
                        seriesCategoryId = payload.seriesCategoryId,
                        liveStreams = payload.liveStreams,
                        movies = payload.movies,
                        series = payload.series,
                        searchResults = buildSearchResults(payload.dashboard, payload.liveStreams, payload.movies, payload.series),
                        error = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to sign in.",
                    )
                }
            }
        }
    }

    fun selectSection(section: AppSection) {
        _uiState.update {
            it.copy(
                selectedSection = section,
                selectedMovieDetail = null,
                selectedSeriesDetail = null,
                error = null,
            )
        }
    }

    fun unlockPin(pin: String) {
        if (preferences.verifyPin(pin)) {
            _uiState.update { it.copy(pinLocked = false, pinError = null) }
        } else {
            _uiState.update { it.copy(pinError = "Incorrect PIN.") }
        }
    }

    fun savePin(pin: String) {
        preferences.savePin(pin)
        _uiState.update { it.copy(hasPin = true, pinLocked = false, pinError = null) }
    }

    fun dismissPlayer() {
        _uiState.update { it.copy(playerRequest = null) }
    }

    fun updateSearchQuery(query: String) {
        val state = _uiState.value
        val all = buildSearchResults(state.dashboard, state.liveStreams, state.movies, state.series)
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = if (query.isBlank()) {
                    all
                } else {
                    all.filter { result ->
                        result.label.contains(query, ignoreCase = true) ||
                            result.detail.contains(query, ignoreCase = true)
                    }
                },
            )
        }
    }

    fun toggleFavorite(key: String) {
        _uiState.update { it.copy(favorites = preferences.toggleFavorite(key)) }
    }

    fun selectLiveCategory(categoryId: String) {
        val state = _uiState.value
        val session = state.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedSection = AppSection.LIVE,
                    liveCategoryId = categoryId,
                    selectedLive = null,
                    epg = emptyList(),
                )
            }
            runCatching {
                repository.loadLiveStreams(session, categoryId)
            }.onSuccess { streams ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        liveStreams = streams,
                        searchResults = buildSearchResults(it.dashboard, streams, it.movies, it.series),
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load channels.") }
            }
        }
    }

    fun selectMovieCategory(categoryId: String) {
        val state = _uiState.value
        val session = state.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedSection = AppSection.MOVIES,
                    movieCategoryId = categoryId,
                    selectedMovieDetail = null,
                )
            }
            runCatching {
                repository.loadVodStreams(session, categoryId)
            }.onSuccess { movies ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        movies = movies,
                        searchResults = buildSearchResults(it.dashboard, it.liveStreams, movies, it.series),
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load movies.") }
            }
        }
    }

    fun selectSeriesCategory(categoryId: String) {
        val state = _uiState.value
        val session = state.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedSection = AppSection.SERIES,
                    seriesCategoryId = categoryId,
                    selectedSeriesDetail = null,
                )
            }
            runCatching {
                repository.loadSeries(session, categoryId)
            }.onSuccess { items ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        series = items,
                        searchResults = buildSearchResults(it.dashboard, it.liveStreams, it.movies, items),
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load series.") }
            }
        }
    }

    fun inspectLive(stream: LiveStream) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedSection = AppSection.LIVE,
                    selectedLive = stream,
                )
            }
            runCatching {
                repository.loadEpg(session, stream.id)
            }.onSuccess { epg ->
                _uiState.update { it.copy(isLoading = false, epg = epg) }
            }.onFailure {
                _uiState.update { it.copy(isLoading = false, epg = emptyList()) }
            }
        }
    }

    fun playLive(stream: LiveStream) {
        val session = _uiState.value.session ?: return
        _uiState.update {
            it.copy(
                playerRequest = PlayerRequest(
                    title = stream.name,
                    subtitle = "Live TV",
                    url = repository.buildLiveUrl(session, stream.id),
                    contentType = ContentType.LIVE,
                ),
            )
        }
    }

    fun playMovie(movie: VodStream) {
        val session = _uiState.value.session ?: return
        _uiState.update {
            it.copy(
                selectedSection = AppSection.MOVIES,
                playerRequest = PlayerRequest(
                    title = movie.name,
                    subtitle = "Movie",
                    url = repository.buildMovieUrl(session, movie.id, movie.extension),
                    contentType = ContentType.MOVIE,
                ),
            )
        }
    }

    fun inspectMovie(movie: VodStream) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedSection = AppSection.MOVIES,
                    selectedMovieDetail = null,
                )
            }
            runCatching {
                repository.loadVodDetail(session, movie.id)
            }.onSuccess { detail ->
                _uiState.update { it.copy(isLoading = false, selectedMovieDetail = detail) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedMovieDetail = VodDetail(
                            stream = movie,
                            plot = null,
                            genre = null,
                            cast = null,
                            durationLabel = null,
                            releaseDate = null,
                            backdropUrl = null,
                        ),
                        error = error.message ?: "Unable to load movie details.",
                    )
                }
            }
        }
    }

    fun closeMovieDetail() {
        _uiState.update { it.copy(selectedMovieDetail = null) }
    }

    fun loadSeriesDetail(item: SeriesItem) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, selectedSection = AppSection.SERIES) }
            runCatching {
                repository.loadSeriesDetail(session, item.id)
            }.onSuccess { detail ->
                _uiState.update { it.copy(isLoading = false, selectedSeriesDetail = detail) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load episodes.") }
            }
        }
    }

    fun closeSeriesDetail() {
        _uiState.update { it.copy(selectedSeriesDetail = null) }
    }

    fun playEpisode(episode: SeriesEpisode) {
        val session = _uiState.value.session ?: return
        _uiState.update {
            it.copy(
                selectedSection = AppSection.SERIES,
                playerRequest = PlayerRequest(
                    title = episode.title,
                    subtitle = "Series episode",
                    url = repository.buildEpisodeUrl(session, episode.id, episode.extension),
                    contentType = ContentType.EPISODE,
                ),
            )
        }
    }

    fun openSearchResult(result: SearchResult) {
        when (result.destinationSection) {
            AppSection.LIVE -> {
                result.categoryId?.let {
                    selectLiveCategory(it)
                    return
                }
                val stream = _uiState.value.liveStreams.firstOrNull { it.favoriteKey() == result.key } ?: return
                selectSection(AppSection.LIVE)
                inspectLive(stream)
            }

            AppSection.MOVIES -> {
                result.categoryId?.let {
                    selectMovieCategory(it)
                    return
                }
                val movie = _uiState.value.movies.firstOrNull { it.favoriteKey() == result.key } ?: return
                selectSection(AppSection.MOVIES)
                inspectMovie(movie)
            }

            AppSection.SERIES -> {
                result.categoryId?.let {
                    selectSeriesCategory(it)
                    return
                }
                val seriesItem = _uiState.value.series.firstOrNull { it.favoriteKey() == result.key } ?: return
                selectSection(AppSection.SERIES)
                loadSeriesDetail(seriesItem)
            }

            AppSection.FAVORITES -> selectSection(AppSection.FAVORITES)
            AppSection.SEARCH -> selectSection(AppSection.SEARCH)
            AppSection.SETTINGS -> selectSection(AppSection.SETTINGS)
            AppSection.HOME -> selectSection(AppSection.HOME)
        }
    }

    fun playCatchup(entry: EpgEntry) {
        val state = _uiState.value
        val session = state.session ?: return
        val stream = state.selectedLive ?: return
        val url = repository.buildCatchupUrl(session, stream.id, entry) ?: return
        _uiState.update {
            it.copy(
                playerRequest = PlayerRequest(
                    title = stream.name,
                    subtitle = "Catch-up: ${entry.title}",
                    url = url,
                    contentType = ContentType.LIVE,
                ),
            )
        }
    }

    companion object {
        fun Factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CrownViewModel(
                        repository = CrownRepository(),
                        preferences = CrownPreferences(context),
                    ) as T
            }
    }
}

private data class LoginPayload(
    val session: ProviderSession,
    val dashboard: DashboardSnapshot,
    val liveCategoryId: String?,
    val movieCategoryId: String?,
    val seriesCategoryId: String?,
    val liveStreams: List<LiveStream>,
    val movies: List<VodStream>,
    val series: List<SeriesItem>,
)

private fun buildSearchResults(
    dashboard: DashboardSnapshot?,
    live: List<LiveStream>,
    movies: List<VodStream>,
    series: List<SeriesItem>,
): List<SearchResult> {
    val results = buildList {
        addAll(
            live.map {
                SearchResult(
                    key = it.favoriteKey(),
                    label = it.name,
                    detail = "Live TV",
                    contentType = ContentType.LIVE,
                    destinationSection = AppSection.LIVE,
                )
            },
        )
        addAll(
            movies.map {
                SearchResult(
                    key = it.favoriteKey(),
                    label = it.name,
                    detail = "Movie",
                    contentType = ContentType.MOVIE,
                    destinationSection = AppSection.MOVIES,
                )
            },
        )
        addAll(
            series.map {
                SearchResult(
                    key = it.favoriteKey(),
                    label = it.name,
                    detail = "Series",
                    contentType = ContentType.SERIES,
                    destinationSection = AppSection.SERIES,
                )
            },
        )
        dashboard?.liveCategories?.forEach {
            add(
                SearchResult(
                    key = "category:live:${it.id}",
                    label = it.name,
                    detail = "Live category",
                    contentType = ContentType.LIVE,
                    destinationSection = AppSection.LIVE,
                    categoryId = it.id,
                ),
            )
        }
        dashboard?.movieCategories?.forEach {
            add(
                SearchResult(
                    key = "category:movie:${it.id}",
                    label = it.name,
                    detail = "Movie category",
                    contentType = ContentType.MOVIE,
                    destinationSection = AppSection.MOVIES,
                    categoryId = it.id,
                ),
            )
        }
        dashboard?.seriesCategories?.forEach {
            add(
                SearchResult(
                    key = "category:series:${it.id}",
                    label = it.name,
                    detail = "Series category",
                    contentType = ContentType.SERIES,
                    destinationSection = AppSection.SERIES,
                    categoryId = it.id,
                ),
            )
        }
    }
    return results.distinctBy { it.key }.take(300)
}

private fun List<ContentCategory>.smartDefaultId(keyword: String): String? =
    firstOrNull { it.name.contains(keyword, ignoreCase = true) }?.id ?: firstOrNull()?.id
