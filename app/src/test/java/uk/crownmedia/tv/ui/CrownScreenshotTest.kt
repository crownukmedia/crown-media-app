package uk.crownmedia.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import uk.crownmedia.tv.data.CrownRepository
import uk.crownmedia.tv.model.AppSection
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
import uk.crownmedia.tv.ui.theme.CrownMediaTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class CrownMobileScreenshotTest {
    @Test
    fun generateScreens() {
        CrownScreenshotHarness(deviceName = "mobile").captureAll()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
class CrownTvScreenshotTest {
    @Test
    fun generateScreens() {
        CrownScreenshotHarness(deviceName = "tv").captureAll()
    }
}

private class CrownScreenshotHarness(
    private val deviceName: String,
) {
    fun captureAll() {
        val fixture = ScreenshotFixtureLoader.fixture
        val outputDir = File("build/reports/screenshots/$deviceName").apply { mkdirs() }

        render(outputDir, "01-login", fixture.loginState)
        render(outputDir, "02-pin", fixture.pinState)
        render(outputDir, "03-home", fixture.homeState)
        render(outputDir, "04-live", fixture.liveState)
        render(outputDir, "05-movies", fixture.moviesState)
        render(outputDir, "06-series", fixture.seriesState)
        render(outputDir, "07-favorites", fixture.favoritesState)
        render(outputDir, "08-search", fixture.searchState)
        render(outputDir, "09-settings", fixture.settingsState)

        assertTrue(outputDir.listFiles()?.isNotEmpty() == true)
    }

    private fun render(outputDir: File, fileName: String, state: CrownUiState) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        activity.setContent {
            CrownMediaTheme {
                CrownMediaAppContent(state = state)
            }
        }

        settleUi()

        val root = activity.window.decorView.rootView
        val width = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val height = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)

        File(outputDir, "$fileName.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        controller.pause().stop().destroy()
    }

    private fun settleUi() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(400)
        }
    }
}

private object ScreenshotFixtureLoader {
    val fixture: ScreenshotFixture by lazy { loadFixture() }

    private fun loadFixture(): ScreenshotFixture = runBlocking {
        val username = credential(env = "CROWN_USERNAME", property = "crown.username")
        val password = credential(env = "CROWN_PASSWORD", property = "crown.password")
        val repository = CrownRepository()

        val session = repository.authenticate(username, password)
        val dashboard = repository.loadDashboard(session)

        val liveCategoryId = dashboard.liveCategories.smartDefaultId("sports")
        val movieCategoryId = dashboard.movieCategories.smartDefaultId("just released")
        val seriesCategoryId = dashboard.seriesCategories.smartDefaultId("netflix")

        val liveStreams = liveCategoryId?.let { repository.loadLiveStreams(session, it).take(8) }.orEmpty()
        val movies = movieCategoryId?.let { repository.loadVodStreams(session, it).take(8) }.orEmpty()
        val series = seriesCategoryId?.let { repository.loadSeries(session, it).take(8) }.orEmpty()
        val selectedLive = liveStreams.firstOrNull()
        val selectedMovie = movies.firstOrNull()
        val selectedSeries = series.firstOrNull()
        val epg = selectedLive?.let { repository.loadEpg(session, it.id).take(4) }.orEmpty()
        val movieDetail = selectedMovie?.let { repository.loadVodDetail(session, it.id) }
        val seriesDetail = selectedSeries?.let { repository.loadSeriesDetail(session, it.id) }
        val favorites = buildFavorites(selectedLive, selectedMovie, selectedSeries, seriesDetail)
        val searchResults = buildSearchResults(dashboard, liveStreams, movies, series).take(24)

        val baseState = CrownUiState(
            session = session,
            dashboard = dashboard,
            selectedSection = AppSection.HOME,
            liveCategoryId = liveCategoryId,
            movieCategoryId = movieCategoryId,
            seriesCategoryId = seriesCategoryId,
            liveStreams = liveStreams,
            movies = movies,
            series = series,
            favorites = favorites,
            searchResults = searchResults,
            lastUsername = username,
        )

        ScreenshotFixture(
            loginState = CrownUiState(lastUsername = username),
            pinState = baseState.copy(pinLocked = true, hasPin = true, pinError = "Incorrect PIN."),
            homeState = baseState,
            liveState = baseState.copy(
                selectedSection = AppSection.LIVE,
                selectedLive = selectedLive,
                epg = epg,
            ),
            moviesState = baseState.copy(
                selectedSection = AppSection.MOVIES,
                selectedMovieDetail = movieDetail,
            ),
            seriesState = baseState.copy(
                selectedSection = AppSection.SERIES,
                selectedSeriesDetail = seriesDetail,
            ),
            favoritesState = baseState.copy(selectedSection = AppSection.FAVORITES),
            searchState = baseState.copy(
                selectedSection = AppSection.SEARCH,
                searchQuery = "UK",
                searchResults = searchResults.filter {
                    it.label.contains("UK", ignoreCase = true) ||
                        it.detail.contains("UK", ignoreCase = true)
                },
            ),
            settingsState = baseState.copy(selectedSection = AppSection.SETTINGS),
        )
    }

    private fun buildFavorites(
        live: LiveStream?,
        movie: VodStream?,
        series: SeriesItem?,
        detail: SeriesDetail?,
    ): Set<String> = buildSet {
        live?.let { add(it.favoriteKey()) }
        movie?.let { add(it.favoriteKey()) }
        series?.let { add(it.favoriteKey()) }
        detail?.episodesBySeason?.values?.flatten()?.firstOrNull()?.let { add(it.favoriteKey()) }
    }

    private fun credential(env: String, property: String): String =
        System.getenv(env)
            ?: System.getProperty(property)
            ?: error("Missing $env environment variable for screenshot generation.")
}

private data class ScreenshotFixture(
    val loginState: CrownUiState,
    val pinState: CrownUiState,
    val homeState: CrownUiState,
    val liveState: CrownUiState,
    val moviesState: CrownUiState,
    val seriesState: CrownUiState,
    val favoritesState: CrownUiState,
    val searchState: CrownUiState,
    val settingsState: CrownUiState,
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
    return results.distinctBy { it.key }
}

private fun List<uk.crownmedia.tv.model.ContentCategory>.smartDefaultId(keyword: String): String? =
    firstOrNull { it.name.contains(keyword, ignoreCase = true) }?.id ?: firstOrNull()?.id
