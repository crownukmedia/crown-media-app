package uk.crownmedia.tv.ui

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import uk.crownmedia.tv.config.CrownConfig
import uk.crownmedia.tv.model.AppSection
import uk.crownmedia.tv.model.ContentCategory
import uk.crownmedia.tv.model.EpgEntry
import uk.crownmedia.tv.model.LiveStream
import uk.crownmedia.tv.model.PlayerRequest
import uk.crownmedia.tv.model.SearchResult
import uk.crownmedia.tv.model.SeriesDetail
import uk.crownmedia.tv.model.SeriesEpisode
import uk.crownmedia.tv.model.SeriesItem
import uk.crownmedia.tv.model.VodDetail
import uk.crownmedia.tv.model.VodStream
import uk.crownmedia.tv.model.favoriteKey
import uk.crownmedia.tv.ui.components.RemoteImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CrownMediaApp(viewModel: CrownViewModel) {
    val state by viewModel.uiState.collectAsState()
    CrownMediaAppContent(
        state = state,
        onLogin = viewModel::login,
        onSetPin = viewModel::savePin,
        onUnlock = viewModel::unlockPin,
        onDismissPlayer = viewModel::dismissPlayer,
        onSelectSection = viewModel::selectSection,
        onSelectLiveCategory = viewModel::selectLiveCategory,
        onSelectMovieCategory = viewModel::selectMovieCategory,
        onSelectSeriesCategory = viewModel::selectSeriesCategory,
        onInspectLive = viewModel::inspectLive,
        onInspectMovie = viewModel::inspectMovie,
        onPlayLive = viewModel::playLive,
        onPlayCatchup = viewModel::playCatchup,
        onPlayMovie = viewModel::playMovie,
        onCloseMovieDetail = viewModel::closeMovieDetail,
        onLoadSeriesDetail = viewModel::loadSeriesDetail,
        onCloseSeriesDetail = viewModel::closeSeriesDetail,
        onPlayEpisode = viewModel::playEpisode,
        onToggleFavorite = viewModel::toggleFavorite,
        onSearch = viewModel::updateSearchQuery,
        onOpenSearchResult = viewModel::openSearchResult,
    )
}

@Composable
internal fun CrownMediaAppContent(
    state: CrownUiState,
    onLogin: (String, String) -> Unit = { _, _ -> },
    onSetPin: (String) -> Unit = {},
    onUnlock: (String) -> Unit = {},
    onDismissPlayer: () -> Unit = {},
    onSelectSection: (AppSection) -> Unit = {},
    onSelectLiveCategory: (String) -> Unit = {},
    onSelectMovieCategory: (String) -> Unit = {},
    onSelectSeriesCategory: (String) -> Unit = {},
    onInspectLive: (LiveStream) -> Unit = {},
    onInspectMovie: (VodStream) -> Unit = {},
    onPlayLive: (LiveStream) -> Unit = {},
    onPlayCatchup: (EpgEntry) -> Unit = {},
    onPlayMovie: (VodStream) -> Unit = {},
    onCloseMovieDetail: () -> Unit = {},
    onLoadSeriesDetail: (SeriesItem) -> Unit = {},
    onCloseSeriesDetail: () -> Unit = {},
    onPlayEpisode: (SeriesEpisode) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onOpenSearchResult: (SearchResult) -> Unit = {},
) {
    val layout = rememberAdaptiveLayout()

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            state.pinLocked -> PinGate(state = state, onUnlock = onUnlock, layout = layout)
            state.session == null -> LoginScreen(
                state = state,
                onLogin = onLogin,
                onSetPin = onSetPin,
                layout = layout,
            )
            state.playerRequest != null -> PlayerScreen(
                request = state.playerRequest,
                onClose = onDismissPlayer,
            )
            else -> AppShell(
                state = state,
                layout = layout,
                onSelectSection = onSelectSection,
                onSelectLiveCategory = onSelectLiveCategory,
                onSelectMovieCategory = onSelectMovieCategory,
                onSelectSeriesCategory = onSelectSeriesCategory,
                onInspectLive = onInspectLive,
                onInspectMovie = onInspectMovie,
                onPlayLive = onPlayLive,
                onPlayCatchup = onPlayCatchup,
                onPlayMovie = onPlayMovie,
                onCloseMovieDetail = onCloseMovieDetail,
                onLoadSeriesDetail = onLoadSeriesDetail,
                onCloseSeriesDetail = onCloseSeriesDetail,
                onPlayEpisode = onPlayEpisode,
                onToggleFavorite = onToggleFavorite,
                onSearch = onSearch,
                onOpenSearchResult = onOpenSearchResult,
            )
        }
    }
}

private data class AdaptiveLayout(
    val compact: Boolean,
    val contentPadding: Dp,
    val sectionSpacing: Dp,
    val cardPadding: Dp,
    val heroHeight: Dp,
    val loginPanelWidth: Dp,
    val pinCardWidth: Dp,
    val categoryWidth: Dp,
    val sidePanelWidth: Dp,
    val shelfCardWidth: Dp,
    val shelfImageHeight: Dp,
    val detailImageHeight: Dp,
)

@Composable
private fun rememberAdaptiveLayout(): AdaptiveLayout {
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 840
    return if (compact) {
        AdaptiveLayout(
            compact = true,
            contentPadding = 16.dp,
            sectionSpacing = 12.dp,
            cardPadding = 20.dp,
            heroHeight = 240.dp,
            loginPanelWidth = 640.dp,
            pinCardWidth = 560.dp,
            categoryWidth = 240.dp,
            sidePanelWidth = 360.dp,
            shelfCardWidth = 220.dp,
            shelfImageHeight = 132.dp,
            detailImageHeight = 160.dp,
        )
    } else {
        AdaptiveLayout(
            compact = false,
            contentPadding = 28.dp,
            sectionSpacing = 18.dp,
            cardPadding = 28.dp,
            heroHeight = 320.dp,
            loginPanelWidth = 520.dp,
            pinCardWidth = 560.dp,
            categoryWidth = 280.dp,
            sidePanelWidth = 420.dp,
            shelfCardWidth = 280.dp,
            shelfImageHeight = 156.dp,
            detailImageHeight = 180.dp,
        )
    }
}

@Composable
private fun CrownBackdrop(
    layout: AdaptiveLayout,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        Color(0xFF050B14),
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (layout.compact) 0.dp else 560.dp,
                    top = if (layout.compact) 220.dp else 80.dp,
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        radius = 620f,
                    ),
                ),
        )
        content()
    }
}

@Composable
private fun LoginScreen(
    state: CrownUiState,
    onLogin: (String, String) -> Unit,
    onSetPin: (String) -> Unit,
    layout: AdaptiveLayout,
) {
    var username by rememberSaveable { mutableStateOf(state.lastUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    val canSubmit = !state.isLoading && username.isNotBlank() && password.isNotBlank()

    fun submitLogin() {
        if (!canSubmit) return
        if (pin.length == 4) {
            onSetPin(pin)
        }
        onLogin(username, password)
    }

    CrownBackdrop(layout = layout) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(layout.contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            val arrangement = Arrangement.spacedBy(layout.sectionSpacing)
            if (layout.compact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = arrangement,
                ) {
                    LoginBrandCard(layout = layout)
                    LoginFormCard(
                        state = state,
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        pin = pin,
                        onPinChange = { pin = it.take(4) },
                        onSubmit = ::submitLogin,
                        onSetPin = onSetPin,
                        canSubmit = canSubmit,
                        layout = layout,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = arrangement,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoginBrandCard(
                        layout = layout,
                        modifier = Modifier.weight(1f),
                    )
                    LoginFormCard(
                        state = state,
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        pin = pin,
                        onPinChange = { pin = it.take(4) },
                        onSubmit = ::submitLogin,
                        onSetPin = onSetPin,
                        canSubmit = canSubmit,
                        layout = layout,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginBrandCard(
    layout: AdaptiveLayout,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BrandKicker("Crown Media TV")
            Text(
                text = CrownConfig.appName,
                style = if (layout.compact) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displayLarge
                },
            )
            Text(
                text = CrownConfig.tagline,
                style = if (layout.compact) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Premium live TV, sports, movies and series for Fire TV and Android TV with fast member sign-in.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            )
            MetricStrip(
                metrics = listOf(
                    "Live TV" to "24/7 access",
                    "Sports" to "Catch-up ready",
                    "Support" to "WhatsApp + email",
                ),
                compact = layout.compact,
            )
            FeatureList(
                items = listOf(
                    "Private provider connection built directly into the app",
                    "Favorites, search, parental PIN and series browsing",
                    "Works on both TV and touch devices",
                ),
            )
            InfoCard(
                label = "Support",
                value = "${CrownConfig.supportWhatsApp}\n${CrownConfig.supportEmail}",
            )
        }
    }
}

@Composable
private fun LoginFormCard(
    state: CrownUiState,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSetPin: (String) -> Unit,
    canSubmit: Boolean,
    layout: AdaptiveLayout,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = layout.loginPanelWidth),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Member sign in", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Enter your account details below to access live TV, movies and series.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text("Parental PIN (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                StatusBanner(
                    text = it,
                    background = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                    foreground = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isLoading) "Signing in..." else "Sign in")
            }
            OutlinedButton(
                onClick = { if (pin.length == 4) onSetPin(pin) },
                enabled = pin.length == 4,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save PIN")
            }
        }
    }
}

@Composable
private fun PinGate(
    state: CrownUiState,
    onUnlock: (String) -> Unit,
    layout: AdaptiveLayout,
) {
    var pin by rememberSaveable { mutableStateOf("") }

    CrownBackdrop(layout = layout) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.pinCardWidth),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier.padding(layout.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BrandKicker("Private mode")
                    Text("Parental PIN", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "This Crown Media app is locked until the 4-digit PIN is entered.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.take(4) },
                        label = { Text("PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.pinError?.let {
                        StatusBanner(
                            text = it,
                            background = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                            foreground = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = { onUnlock(pin) }, enabled = pin.length == 4) {
                        Text("Unlock")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppShell(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSelectSection: (AppSection) -> Unit,
    onSelectLiveCategory: (String) -> Unit,
    onSelectMovieCategory: (String) -> Unit,
    onSelectSeriesCategory: (String) -> Unit,
    onInspectLive: (LiveStream) -> Unit,
    onInspectMovie: (VodStream) -> Unit,
    onPlayLive: (LiveStream) -> Unit,
    onPlayCatchup: (EpgEntry) -> Unit,
    onPlayMovie: (VodStream) -> Unit,
    onCloseMovieDetail: () -> Unit,
    onLoadSeriesDetail: (SeriesItem) -> Unit,
    onCloseSeriesDetail: () -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenSearchResult: (SearchResult) -> Unit,
) {
    CrownBackdrop(layout = layout) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layout.contentPadding, vertical = layout.contentPadding),
            verticalArrangement = Arrangement.spacedBy(layout.sectionSpacing),
        ) {
            Header(state = state, onSelectSection = onSelectSection, layout = layout)
            if (state.isLoading) {
                StatusBanner(
                    text = "Refreshing your content library...",
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    foreground = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let {
                StatusBanner(
                    text = it,
                    background = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                    foreground = MaterialTheme.colorScheme.error,
                )
            }
            when (state.selectedSection) {
                AppSection.HOME -> HomeScreen(
                    state = state,
                    layout = layout,
                    onSelectSection = onSelectSection,
                    onPlayLive = onPlayLive,
                    onInspectMovie = onInspectMovie,
                    onLoadSeriesDetail = onLoadSeriesDetail,
                )
                AppSection.LIVE -> LiveScreen(
                    state = state,
                    layout = layout,
                    onSelectCategory = onSelectLiveCategory,
                    onInspectLive = onInspectLive,
                    onPlayLive = onPlayLive,
                    onPlayCatchup = onPlayCatchup,
                    onToggleFavorite = onToggleFavorite,
                )
                AppSection.MOVIES -> MovieScreen(
                    state = state,
                    layout = layout,
                    onSelectCategory = onSelectMovieCategory,
                    onInspectMovie = onInspectMovie,
                    onPlayMovie = onPlayMovie,
                    onCloseMovieDetail = onCloseMovieDetail,
                    onToggleFavorite = onToggleFavorite,
                )
                AppSection.SERIES -> SeriesScreen(
                    state = state,
                    layout = layout,
                    onSelectCategory = onSelectSeriesCategory,
                    onLoadSeriesDetail = onLoadSeriesDetail,
                    onCloseSeriesDetail = onCloseSeriesDetail,
                    onPlayEpisode = onPlayEpisode,
                    onToggleFavorite = onToggleFavorite,
                )
                AppSection.FAVORITES -> FavoritesScreen(
                    state = state,
                    layout = layout,
                    onInspectLive = onInspectLive,
                    onInspectMovie = onInspectMovie,
                    onLoadSeriesDetail = onLoadSeriesDetail,
                    onPlayEpisode = onPlayEpisode,
                    onToggleFavorite = onToggleFavorite,
                )
                AppSection.SEARCH -> SearchScreen(
                    state = state,
                    layout = layout,
                    onSearch = onSearch,
                    onOpenResult = onOpenSearchResult,
                )
                AppSection.SETTINGS -> SettingsScreen(state = state, layout = layout)
            }
        }
    }
}

@Composable
private fun Header(
    state: CrownUiState,
    onSelectSection: (AppSection) -> Unit,
    layout: AdaptiveLayout,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (layout.compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(CrownConfig.appName, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        CrownConfig.tagline,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HeaderSessionCard(state = state, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(CrownConfig.appName, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        CrownConfig.tagline,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HeaderSessionCard(state = state)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AppSection.entries.toList()) { section ->
                SectionPill(
                    title = section.title(),
                    selected = state.selectedSection == section,
                    onClick = { onSelectSection(section) },
                )
            }
        }
    }
}

@Composable
private fun HeaderSessionCard(
    state: CrownUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Signed in as ${state.session?.credentials?.username ?: state.lastUsername}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Favorites ${state.favorites.size}  |  Expires ${formatExpiry(state.session?.expiryEpochSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSelectSection: (AppSection) -> Unit,
    onPlayLive: (LiveStream) -> Unit,
    onInspectMovie: (VodStream) -> Unit,
    onLoadSeriesDetail: (SeriesItem) -> Unit,
) {
    val dashboard = state.dashboard
    val heroSeries = dashboard?.featuredSeries?.firstOrNull()
    val heroMovie = dashboard?.featuredMovies?.firstOrNull()
    val heroLive = dashboard?.featuredLive?.firstOrNull()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.heroHeight),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                Box {
                    RemoteImage(
                        url = heroSeries?.backdropUrl ?: heroMovie?.iconUrl,
                        contentDescription = heroSeries?.name ?: heroMovie?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        fallbackLabel = CrownConfig.appName,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xE6081120),
                                        Color(0xC7081120),
                                        Color(0x66081120),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(layout.cardPadding),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            BrandKicker("Premium TV experience")
                            Text(
                                text = heroSeries?.name ?: heroMovie?.name ?: "Curated for tonight",
                                style = if (layout.compact) {
                                    MaterialTheme.typography.headlineLarge
                                } else {
                                    MaterialTheme.typography.displayLarge
                                },
                            )
                            Text(
                                text = heroSeries?.plot
                                    ?: "Crown Media is tuned for live TV, sports, films and series with clean Fire TV navigation and fast Xtream login.",
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            MetricStrip(
                                metrics = listOf(
                                    "Live categories" to "${dashboard?.liveCategories?.size ?: 0}",
                                    "Movie categories" to "${dashboard?.movieCategories?.size ?: 0}",
                                    "Series categories" to "${dashboard?.seriesCategories?.size ?: 0}",
                                    "Saved items" to "${state.favorites.size}",
                                ),
                                compact = layout.compact,
                            )
                        }
                        if (layout.compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                heroLive?.let { live ->
                                    Button(onClick = { onPlayLive(live) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Play live now")
                                    }
                                }
                                heroMovie?.let { movie ->
                                    OutlinedButton(
                                        onClick = { onInspectMovie(movie) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Open featured movie")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onSelectSection(AppSection.FAVORITES) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Open favorites")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                heroLive?.let { live ->
                                    Button(onClick = { onPlayLive(live) }) { Text("Play live now") }
                                }
                                heroMovie?.let { movie ->
                                    OutlinedButton(onClick = { onInspectMovie(movie) }) { Text("Open featured movie") }
                                }
                                OutlinedButton(onClick = { onSelectSection(AppSection.FAVORITES) }) {
                                    Text("Open favorites")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            QuickActionRow(onSelectSection = onSelectSection, layout = layout)
        }
        item {
            MediaShelf(
                layout = layout,
                title = "Featured live",
                subtitle = "Fast access to the first live channels loaded from your default category.",
                items = dashboard?.featuredLive.orEmpty().map {
                    ShelfCardData(
                        title = it.name,
                        detail = if (it.hasCatchup) "Catch-up ready" else "Live TV",
                        imageUrl = it.iconUrl,
                        onClick = { onPlayLive(it) },
                    )
                },
            )
        }
        item {
            MediaShelf(
                layout = layout,
                title = "Featured movies",
                subtitle = "Premium VOD picks from the first movie category.",
                items = dashboard?.featuredMovies.orEmpty().map {
                    ShelfCardData(
                        title = it.name,
                        detail = it.rating?.let { rating -> "Rating $rating" } ?: "Movie",
                        imageUrl = it.iconUrl,
                        onClick = { onInspectMovie(it) },
                    )
                },
            )
        }
        item {
            MediaShelf(
                layout = layout,
                title = "Featured series",
                subtitle = "Open a series to jump into seasons and episodes.",
                items = dashboard?.featuredSeries.orEmpty().map {
                    ShelfCardData(
                        title = it.name,
                        detail = it.rating?.let { rating -> "Rating $rating" } ?: "Series",
                        imageUrl = it.coverUrl ?: it.backdropUrl,
                        onClick = { onLoadSeriesDetail(it) },
                    )
                },
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    onSelectSection: (AppSection) -> Unit,
    layout: AdaptiveLayout,
) {
    val cards = listOf(
        QuickActionData(
            title = "Live dashboard",
            detail = "Open channel categories and EPG",
            onClick = { onSelectSection(AppSection.LIVE) },
        ),
        QuickActionData(
            title = "Movie library",
            detail = "Inspect VOD details and play instantly",
            onClick = { onSelectSection(AppSection.MOVIES) },
        ),
        QuickActionData(
            title = "Series browser",
            detail = "Jump into seasons, episodes and saved picks",
            onClick = { onSelectSection(AppSection.SERIES) },
        ),
    )

    if (layout.compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            cards.forEach { card ->
                QuickActionCard(
                    title = card.title,
                    detail = card.detail,
                    onClick = card.onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            cards.forEach { card ->
                QuickActionCard(
                    title = card.title,
                    detail = card.detail,
                    onClick = card.onClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiveScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSelectCategory: (String) -> Unit,
    onInspectLive: (LiveStream) -> Unit,
    onPlayLive: (LiveStream) -> Unit,
    onPlayCatchup: (EpgEntry) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (layout.compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            CategorySelector(
                title = "Live categories",
                categories = state.dashboard?.liveCategories.orEmpty(),
                selectedId = state.liveCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.liveStreams) { stream ->
                    ContentRowCard(
                        title = stream.name,
                        subtitle = if (stream.hasCatchup) "Catch-up available" else "Live TV",
                        imageUrl = stream.iconUrl,
                        saved = state.favorites.contains(stream.favoriteKey()),
                        onOpen = { onInspectLive(stream) },
                        onPrimary = { onPlayLive(stream) },
                        primaryLabel = "Play",
                        onSave = { onToggleFavorite(stream.favoriteKey()) },
                        layout = layout,
                    )
                }
            }
            LiveDetailPanel(
                stream = state.selectedLive,
                epg = state.epg,
                onPlayLive = onPlayLive,
                onPlayCatchup = onPlayCatchup,
                layout = layout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            CategoryColumn(
                title = "Live categories",
                categories = state.dashboard?.liveCategories.orEmpty(),
                selectedId = state.liveCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.liveStreams) { stream ->
                    ContentRowCard(
                        title = stream.name,
                        subtitle = if (stream.hasCatchup) "Catch-up available" else "Live TV",
                        imageUrl = stream.iconUrl,
                        saved = state.favorites.contains(stream.favoriteKey()),
                        onOpen = { onInspectLive(stream) },
                        onPrimary = { onPlayLive(stream) },
                        primaryLabel = "Play",
                        onSave = { onToggleFavorite(stream.favoriteKey()) },
                        layout = layout,
                    )
                }
            }
            LiveDetailPanel(
                stream = state.selectedLive,
                epg = state.epg,
                onPlayLive = onPlayLive,
                onPlayCatchup = onPlayCatchup,
                layout = layout,
            )
        }
    }
}

@Composable
private fun MovieScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSelectCategory: (String) -> Unit,
    onInspectMovie: (VodStream) -> Unit,
    onPlayMovie: (VodStream) -> Unit,
    onCloseMovieDetail: () -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (layout.compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            CategorySelector(
                title = "Movie categories",
                categories = state.dashboard?.movieCategories.orEmpty(),
                selectedId = state.movieCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.movies) { movie ->
                    ContentRowCard(
                        title = movie.name,
                        subtitle = movie.rating?.let { "Rating $it" } ?: "Movie",
                        imageUrl = movie.iconUrl,
                        saved = state.favorites.contains(movie.favoriteKey()),
                        onOpen = { onInspectMovie(movie) },
                        onPrimary = { onPlayMovie(movie) },
                        primaryLabel = "Play",
                        onSave = { onToggleFavorite(movie.favoriteKey()) },
                        layout = layout,
                    )
                }
            }
            MovieDetailPanel(
                detail = state.selectedMovieDetail,
                onPlayMovie = onPlayMovie,
                onClose = onCloseMovieDetail,
                layout = layout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            CategoryColumn(
                title = "Movie categories",
                categories = state.dashboard?.movieCategories.orEmpty(),
                selectedId = state.movieCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.movies) { movie ->
                    ContentRowCard(
                        title = movie.name,
                        subtitle = movie.rating?.let { "Rating $it" } ?: "Movie",
                        imageUrl = movie.iconUrl,
                        saved = state.favorites.contains(movie.favoriteKey()),
                        onOpen = { onInspectMovie(movie) },
                        onPrimary = { onPlayMovie(movie) },
                        primaryLabel = "Play",
                        onSave = { onToggleFavorite(movie.favoriteKey()) },
                        layout = layout,
                    )
                }
            }
            MovieDetailPanel(
                detail = state.selectedMovieDetail,
                onPlayMovie = onPlayMovie,
                onClose = onCloseMovieDetail,
                layout = layout,
            )
        }
    }
}

@Composable
private fun SeriesScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSelectCategory: (String) -> Unit,
    onLoadSeriesDetail: (SeriesItem) -> Unit,
    onCloseSeriesDetail: () -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (layout.compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            CategorySelector(
                title = "Series categories",
                categories = state.dashboard?.seriesCategories.orEmpty(),
                selectedId = state.seriesCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            if (state.selectedSeriesDetail == null) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.series) { item ->
                        ContentRowCard(
                            title = item.name,
                            subtitle = item.rating?.let { "Rating $it" } ?: "Series",
                            imageUrl = item.coverUrl,
                            saved = state.favorites.contains(item.favoriteKey()),
                            onOpen = { onLoadSeriesDetail(item) },
                            onPrimary = { onLoadSeriesDetail(item) },
                            primaryLabel = "Episodes",
                            onSave = { onToggleFavorite(item.favoriteKey()) },
                            layout = layout,
                        )
                    }
                }
            } else {
                SeriesDetailPanel(
                    detail = state.selectedSeriesDetail,
                    favorites = state.favorites,
                    onClose = onCloseSeriesDetail,
                    onPlayEpisode = onPlayEpisode,
                    onToggleFavorite = onToggleFavorite,
                    layout = layout,
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            CategoryColumn(
                title = "Series categories",
                categories = state.dashboard?.seriesCategories.orEmpty(),
                selectedId = state.seriesCategoryId,
                onSelect = onSelectCategory,
                layout = layout,
            )
            if (state.selectedSeriesDetail == null) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.series) { item ->
                        ContentRowCard(
                            title = item.name,
                            subtitle = item.rating?.let { "Rating $it" } ?: "Series",
                            imageUrl = item.coverUrl,
                            saved = state.favorites.contains(item.favoriteKey()),
                            onOpen = { onLoadSeriesDetail(item) },
                            onPrimary = { onLoadSeriesDetail(item) },
                            primaryLabel = "Episodes",
                            onSave = { onToggleFavorite(item.favoriteKey()) },
                            layout = layout,
                        )
                    }
                }
            } else {
                SeriesDetailPanel(
                    detail = state.selectedSeriesDetail,
                    favorites = state.favorites,
                    onClose = onCloseSeriesDetail,
                    onPlayEpisode = onPlayEpisode,
                    onToggleFavorite = onToggleFavorite,
                    layout = layout,
                )
            }
        }
    }
}

@Composable
private fun FavoritesScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onInspectLive: (LiveStream) -> Unit,
    onInspectMovie: (VodStream) -> Unit,
    onLoadSeriesDetail: (SeriesItem) -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val favoriteLive = state.liveStreams.filter { state.favorites.contains(it.favoriteKey()) }
    val favoriteMovies = state.movies.filter { state.favorites.contains(it.favoriteKey()) }
    val favoriteSeries = state.series.filter { state.favorites.contains(it.favoriteKey()) }
    val favoriteEpisodes = state.selectedSeriesDetail
        ?.episodesBySeason
        ?.values
        ?.flatten()
        ?.filter { state.favorites.contains(it.favoriteKey()) }
        .orEmpty()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Favorites", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Saved items stay local on the device. Episode favorites appear here once that series detail has been opened in the current session.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (favoriteLive.isEmpty() && favoriteMovies.isEmpty() && favoriteSeries.isEmpty() && favoriteEpisodes.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No saved content yet",
                    detail = "Save channels, movies, series or episodes from the other sections to build a quick-access shelf here.",
                )
            }
        }
        if (favoriteLive.isNotEmpty()) {
            item { SectionTitle("Saved live channels", "${favoriteLive.size} channels") }
            items(favoriteLive) { stream ->
                ContentRowCard(
                    title = stream.name,
                    subtitle = if (stream.hasCatchup) "Catch-up available" else "Live TV",
                    imageUrl = stream.iconUrl,
                    saved = true,
                    onOpen = { onInspectLive(stream) },
                    onPrimary = { onInspectLive(stream) },
                    primaryLabel = "Open",
                    onSave = { onToggleFavorite(stream.favoriteKey()) },
                    layout = layout,
                )
            }
        }
        if (favoriteMovies.isNotEmpty()) {
            item { SectionTitle("Saved movies", "${favoriteMovies.size} titles") }
            items(favoriteMovies) { movie ->
                ContentRowCard(
                    title = movie.name,
                    subtitle = movie.rating?.let { "Rating $it" } ?: "Movie",
                    imageUrl = movie.iconUrl,
                    saved = true,
                    onOpen = { onInspectMovie(movie) },
                    onPrimary = { onInspectMovie(movie) },
                    primaryLabel = "Open",
                    onSave = { onToggleFavorite(movie.favoriteKey()) },
                    layout = layout,
                )
            }
        }
        if (favoriteSeries.isNotEmpty()) {
            item { SectionTitle("Saved series", "${favoriteSeries.size} series") }
            items(favoriteSeries) { item ->
                ContentRowCard(
                    title = item.name,
                    subtitle = item.rating?.let { "Rating $it" } ?: "Series",
                    imageUrl = item.coverUrl,
                    saved = true,
                    onOpen = { onLoadSeriesDetail(item) },
                    onPrimary = { onLoadSeriesDetail(item) },
                    primaryLabel = "Episodes",
                    onSave = { onToggleFavorite(item.favoriteKey()) },
                    layout = layout,
                )
            }
        }
        if (favoriteEpisodes.isNotEmpty()) {
            item { SectionTitle("Saved episodes", "${favoriteEpisodes.size} episodes") }
            items(favoriteEpisodes) { episode ->
                ContentRowCard(
                    title = episode.title,
                    subtitle = episode.durationLabel ?: "Episode",
                    imageUrl = episode.posterUrl,
                    saved = true,
                    onOpen = { onPlayEpisode(episode) },
                    onPrimary = { onPlayEpisode(episode) },
                    primaryLabel = "Play",
                    onSave = { onToggleFavorite(episode.favoriteKey()) },
                    layout = layout,
                )
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
    onSearch: (String) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            label = { Text("Search loaded content and categories") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            "${state.searchResults.size} result(s) across live TV, movies, series and categories",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.searchResults) { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                ) {
                    if (layout.compact) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(result.label, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${result.detail}  |  ${result.destinationSection.title()}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            OutlinedButton(onClick = { onOpenResult(result) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (result.categoryId == null) "Open" else "Browse")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(result.label, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${result.detail}  |  ${result.destinationSection.title()}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            OutlinedButton(onClick = { onOpenResult(result) }) {
                                Text(if (result.categoryId == null) "Open" else "Browse")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: CrownUiState,
    layout: AdaptiveLayout,
) {
    val session = state.session

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            InfoCard(
                label = "Support",
                value = "${CrownConfig.supportWhatsApp}\n${CrownConfig.supportEmail}",
                modifier = settingsCardModifier(layout),
            )
        }
        item {
            InfoCard(
                label = "Website",
                value = CrownConfig.website,
                modifier = settingsCardModifier(layout),
            )
        }
        item {
            InfoCard(
                label = "Business",
                value = "${CrownConfig.businessAddress}\n${CrownConfig.businessHours}",
                modifier = settingsCardModifier(layout),
            )
        }
        item {
            InfoCard(
                label = "Account session",
                value = buildString {
                    append("Status: ${session?.status ?: "n/a"}")
                    append("\nMax connections: ${session?.maxConnections ?: 0}")
                    append(
                        "\nAllowed formats: ${
                            session?.allowedFormats?.joinToString().orEmpty().ifBlank { "n/a" }
                        }",
                    )
                    append("\nExpiry: ${formatExpiry(session?.expiryEpochSeconds)}")
                },
                modifier = settingsCardModifier(layout),
            )
        }
        item {
            InfoCard(
                label = "Highlights",
                value = "Private provider integration, parental PIN support, favorites, search, live EPG, catch-up playback, VOD details and TV-first navigation.",
                modifier = settingsCardModifier(layout),
            )
        }
    }
}

@Composable
private fun CategoryColumn(
    title: String,
    categories: List<ContentCategory>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    layout: AdaptiveLayout,
) {
    Column(
        modifier = Modifier
            .width(layout.categoryWidth)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { category ->
                SectionPill(
                    title = category.name,
                    selected = category.id == selectedId,
                    onClick = { onSelect(category.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CategorySelector(
    title: String,
    categories: List<ContentCategory>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    layout: AdaptiveLayout,
) {
    if (layout.compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { category ->
                    SectionPill(
                        title = category.name,
                        selected = category.id == selectedId,
                        onClick = { onSelect(category.id) },
                    )
                }
            }
        }
    } else {
        CategoryColumn(
            title = title,
            categories = categories,
            selectedId = selectedId,
            onSelect = onSelect,
            layout = layout,
        )
    }
}

@Composable
private fun LiveDetailPanel(
    stream: LiveStream?,
    epg: List<EpgEntry>,
    onPlayLive: (LiveStream) -> Unit,
    onPlayCatchup: (EpgEntry) -> Unit,
    layout: AdaptiveLayout,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.then(
            if (layout.compact) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .width(layout.sidePanelWidth)
                    .fillMaxHeight()
            },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stream?.name ?: "Channel details", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (stream?.hasCatchup == true) {
                    "EPG and catch-up ready for the selected channel."
                } else {
                    "Select a live channel to view programming details."
                },
                color = MaterialTheme.colorScheme.primary,
            )
            stream?.let {
                MetricStrip(
                    metrics = listOf(
                        "Stream" to it.id,
                        "Catch-up" to if (it.hasCatchup) "${it.catchupWindowHours}h" else "Off",
                    ),
                    compact = layout.compact,
                )
                Button(onClick = { onPlayLive(it) }) { Text("Play live") }
            }
            if (epg.isEmpty()) {
                EmptyStateCard(
                    title = "No EPG loaded yet",
                    detail = "Open a channel from the list to populate this panel with now/next programming.",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(epg) { item ->
                        Card {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(programTimeRange(item), style = MaterialTheme.typography.bodyMedium)
                                item.description?.takeIf(String::isNotBlank)?.let { desc ->
                                    Text(desc, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (stream?.hasCatchup == true) {
                                    OutlinedButton(onClick = { onPlayCatchup(item) }) {
                                        Text("Play from start")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieDetailPanel(
    detail: VodDetail?,
    onPlayMovie: (VodStream) -> Unit,
    onClose: () -> Unit,
    layout: AdaptiveLayout,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.then(
            if (layout.compact) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .width(layout.sidePanelWidth)
                    .fillMaxHeight()
            },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        if (detail == null) {
            EmptyStateCard(
                title = "Movie details",
                detail = "Choose a title from the center list to load richer VOD metadata and a focused play panel.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(18.dp)) {
                item {
                    RemoteImage(
                        url = detail.backdropUrl ?: detail.stream.iconUrl,
                        contentDescription = detail.stream.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(layout.detailImageHeight)
                            .clip(RoundedCornerShape(18.dp)),
                        fallbackLabel = detail.stream.name,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onClose) { Text("Back") }
                        Button(onClick = { onPlayMovie(detail.stream) }) { Text("Play movie") }
                    }
                }
                item {
                    Text(detail.stream.name, style = MaterialTheme.typography.headlineLarge)
                }
                item {
                    MetricStrip(
                        metrics = listOfNotNull(
                            detail.stream.rating?.let { "Rating" to it },
                            detail.durationLabel?.let { "Duration" to it },
                            detail.releaseDate?.let { "Release" to it },
                        ),
                        compact = layout.compact,
                    )
                }
                detail.genre?.let {
                    item { InfoCard("Genre", it) }
                }
                detail.cast?.let {
                    item { InfoCard("Cast", it) }
                }
                item {
                    InfoCard("Synopsis", detail.plot ?: "No synopsis returned by the provider.")
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailPanel(
    detail: SeriesDetail,
    favorites: Set<String>,
    onClose: () -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onToggleFavorite: (String) -> Unit,
    layout: AdaptiveLayout,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteImage(
            url = detail.item.backdropUrl ?: detail.item.coverUrl,
            contentDescription = detail.item.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (layout.compact) 180.dp else 220.dp)
                .clip(RoundedCornerShape(20.dp)),
            fallbackLabel = detail.item.name,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onClose) { Text("Back") }
            Text(detail.item.name, style = MaterialTheme.typography.headlineLarge)
        }
        MetricStrip(
            metrics = listOfNotNull(
                detail.item.rating?.let { "Rating" to it },
                detail.genre?.let { "Genre" to it },
                detail.cast?.takeIf(String::isNotBlank)?.let { "Cast" to it.take(28) },
            ),
            compact = layout.compact,
        )
        Text(detail.item.plot ?: "No synopsis available.", maxLines = 4, overflow = TextOverflow.Ellipsis)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            detail.episodesBySeason.forEach { (season, episodes) ->
                item {
                    Text(
                        "Season $season",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(episodes) { episode ->
                    ContentRowCard(
                        title = episode.title,
                        subtitle = episode.durationLabel ?: "Episode",
                        imageUrl = episode.posterUrl,
                        saved = favorites.contains(episode.favoriteKey()),
                        onOpen = { onPlayEpisode(episode) },
                        onPrimary = { onPlayEpisode(episode) },
                        primaryLabel = "Play",
                        onSave = { onToggleFavorite(episode.favoriteKey()) },
                        layout = layout,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentRowCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    saved: Boolean,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    primaryLabel: String,
    onSave: () -> Unit,
    layout: AdaptiveLayout,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = imageUrl,
                contentDescription = title,
                modifier = Modifier
                    .size(
                        width = if (layout.compact) 88.dp else 120.dp,
                        height = if (layout.compact) 88.dp else 74.dp,
                    )
                    .clip(RoundedCornerShape(12.dp)),
                fallbackLabel = title,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (layout.compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPrimary) { Text(primaryLabel) }
                    OutlinedButton(onClick = onSave) { Text(if (saved) "Saved" else "Save") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPrimary) { Text(primaryLabel) }
                    OutlinedButton(onClick = onSave) { Text(if (saved) "Saved" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun SectionPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaShelf(
    layout: AdaptiveLayout,
    title: String,
    subtitle: String,
    items: List<ShelfCardData>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        if (items.isEmpty()) {
            EmptyStateCard(
                title = "Nothing loaded yet",
                detail = "This shelf will fill in once the provider returns featured content for the default category.",
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items) { item ->
                    Card(
                        modifier = Modifier
                            .width(layout.shelfCardWidth)
                            .clickable(onClick = item.onClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            RemoteImage(
                                url = item.imageUrl,
                                contentDescription = item.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(layout.shelfImageHeight)
                                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                                fallbackLabel = item.title,
                            )
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun BrandKicker(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MetricStrip(
    metrics: List<Pair<String, String>>,
    compact: Boolean = false,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { (label, value) ->
                        MetricCard(
                            label = label,
                            value = value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(metrics) { (label, value) ->
                MetricCard(label = label, value = value)
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FeatureList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Text(
                text = "- $item",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusBanner(
    text: String,
    background: Color,
    foreground: Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.25f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = foreground,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    detail: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun InfoCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Text(value)
        }
    }
}

@Composable
private fun PlayerScreen(
    request: PlayerRequest?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(request?.url) {
        ExoPlayer.Builder(context).build().apply {
            request?.url?.let {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
                playWhenReady = true
            }
        }
    }

    LaunchedEffect(request?.url) {
        request?.url?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                }
            },
            update = { it.player = player },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(request?.title ?: "", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text(request?.subtitle ?: "", color = Color.White.copy(alpha = 0.85f))
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}

private data class ShelfCardData(
    val title: String,
    val detail: String,
    val imageUrl: String?,
    val onClick: () -> Unit,
)

private data class QuickActionData(
    val title: String,
    val detail: String,
    val onClick: () -> Unit,
)

private fun AppSection.title(): String =
    name.lowercase().replaceFirstChar(Char::uppercaseChar)

private fun formatExpiry(expiryEpochSeconds: Long?): String {
    if (expiryEpochSeconds == null || expiryEpochSeconds <= 0L) {
        return "Unknown"
    }
    return runCatching {
        Instant.ofEpochSecond(expiryEpochSeconds)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }.getOrDefault("Unknown")
}

private fun programTimeRange(entry: EpgEntry): String {
    val formatter = DateTimeFormatter.ofPattern("dd MMM  HH:mm")
    val start = entry.startEpochSeconds?.let {
        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(formatter)
    } ?: entry.start
    val end = entry.endEpochSeconds?.let {
        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(formatter)
    } ?: entry.end
    return "$start to $end"
}

private fun settingsCardModifier(layout: AdaptiveLayout): Modifier =
    Modifier
        .fillMaxWidth()
        .widthIn(max = if (layout.compact) 640.dp else 860.dp)
