package uk.crownmedia.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import uk.crownmedia.app.databinding.ActivityMainBinding
import uk.crownmedia.core.database.CrownDatabase
import uk.crownmedia.core.design.StreamAvailability
import uk.crownmedia.core.model.ProviderCredentials
import uk.crownmedia.data.xtream.XtreamCategory
import uk.crownmedia.data.xtream.XtreamClient
import uk.crownmedia.data.xtream.XtreamItem
import uk.crownmedia.data.xtream.XtreamSeriesDetails
import uk.crownmedia.data.xtream.preferredLiveExtension
import uk.crownmedia.player.PlayerActivity
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.EnumMap

class MainActivity : AppCompatActivity() {
    private val internalPlayer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::store.isInitialized && section == Section.LIVE) {
            store.selected()?.let { playlist -> lifecycleScope.launch { renderCachedLive(playlist) } }
        }
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AppStore
    private lateinit var cache: CatalogCache
    private lateinit var streamAvailability: StreamAvailability
    private val api = XtreamClient()
    private lateinit var categoriesAdapter: CategoryAdapter
    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var contentLayoutManager: GridLayoutManager
    private var section = Section.HOME
    private var currentCategory = "all"
    private var loadJob: Job? = null
    private var loginJob: Job? = null
    private var searchJob: Job? = null
    private var healthJob: Job? = null
    private var liveRankingJob: Job? = null
    private var playlistRefreshJob: Job? = null
    private var detailJob: Job? = null
    private var searchWarmJob: Deferred<Result<Unit>>? = null
    private var searchWarmCompletedFor: String? = null
    private var lastCategories = emptyList<XtreamCategory>()
    private val sectionStates = EnumMap<Section, SectionState>(Section::class.java)
    private val refreshJobs = EnumMap<Section, Job>(Section::class.java)
    private val catalogRefreshPermit = Semaphore(1)
    private var activePlaylistId: String? = null
    private var nestedSeries: SeriesDetailState? = null
    private var stateRetry: (() -> Unit)? = null
    private var loginGeneration = 0L
    private var loginService = CrownService.default
    private var updatingLoginForm = false
    private var adultUnlockedUntil = 0L
    private var lastBroadHealthSampleAt = 0L
    private var pendingContentFocusKey: String? = null
    private var searchShouldFocusResults = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = storeFactory(this)
        cache = CatalogCache(CrownDatabase.get(this).catalogDao())
        streamAvailability = StreamAvailability(this)
        configureLogin()
        configureLists()
        configureNavigation()
        configureSearch()
        ViewCompat.setAccessibilityHeading(binding.stateTitle, true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.loginPanel.root.isVisible && store.selected() != null) {
                    cancelLogin()
                    open(Section.HOME)
                } else if (nestedSeries != null) {
                    closeSeriesDetails()
                } else if (section == Section.SEARCH) {
                    hideKeyboard()
                    open(Section.HOME)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (store.selected() == null) showWelcome() else {
            refreshPlaybackCapabilitiesIfNeeded()
            open(Section.HOME)
        }
    }

    private fun configureLists() {
        categoriesAdapter = CategoryAdapter(::selectCategory, ::hideCategory)
        binding.categoryList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryList.adapter = categoriesAdapter
        catalogAdapter = CatalogAdapter(::openCard, ::cardOptions)
        val television = deviceClass() == DeviceClass.TELEVISION
        val initialColumns = when {
            television -> 5
            resources.configuration.smallestScreenWidthDp >= 600 -> 4
            else -> 1
        }
        contentLayoutManager = GridLayoutManager(this, initialColumns)
        binding.contentGrid.layoutManager = contentLayoutManager
        binding.contentGrid.adapter = catalogAdapter
        binding.contentGrid.setHasFixedSize(true)
        binding.contentGrid.setItemViewCacheSize(8)
        contentLayoutManager.initialPrefetchItemCount = 8
        binding.contentGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || section !in PAGED_SECTIONS) return
                val lastVisible = contentLayoutManager.findLastVisibleItemPosition()
                if (lastVisible >= catalogAdapter.itemCount - PREFETCH_DISTANCE) loadNextPage()
            }
        })
        binding.categoryList.setHasFixedSize(true)
        binding.categoryList.setItemViewCacheSize(10)
        if (!television) binding.contentGrid.doOnLayout { grid ->
            val available = grid.width - grid.paddingStart - grid.paddingEnd
            val minimumCardWidth = (if (resources.configuration.smallestScreenWidthDp >= 600) 190 else 164) * resources.displayMetrics.density
            contentLayoutManager.spanCount = (available / minimumCardWidth).toInt().coerceIn(1, 6)
        }
    }

    private fun configureNavigation() {
        binding.navHome.setOnClickListener { open(Section.HOME) }
        binding.navLive.setOnClickListener { open(Section.LIVE) }
        binding.navMovies.setOnClickListener { open(Section.MOVIES) }
        binding.navSeries.setOnClickListener { open(Section.SERIES) }
        binding.navSearch.setOnClickListener { open(Section.SEARCH) }
        binding.navAccount.setOnClickListener { showAccount() }
        binding.navSettings.setOnClickListener { showSettings() }
        binding.navExit.setOnClickListener { confirmExit() }
        binding.actionReload.setOnClickListener { if (section == Section.HOME) refreshAllCatalogs() else load(force = true) }
        binding.actionPlaylist.setOnClickListener { showPlaylists() }
        binding.actionMore.setOnClickListener(::showMobileMenu)
        binding.actionSearchClear.setOnClickListener {
            if (binding.searchBox.text.isNotEmpty()) binding.searchBox.text.clear() else open(Section.HOME)
        }
        binding.stateAction.setOnClickListener {
            val retry = stateRetry
            stateRetry = null
            if (retry != null) retry() else if (store.selected() == null) showManualPlaylist() else load(true)
        }
        if (deviceClass() == DeviceClass.TELEVISION) {
            listOf(binding.navHome, binding.navLive, binding.navMovies, binding.navSeries, binding.navSearch, binding.navAccount, binding.navSettings, binding.navExit)
                .forEach { button ->
                    button.setOnFocusChangeListener { view, focused ->
                        (view as? com.google.android.material.button.MaterialButton)?.strokeWidth =
                            ((if (focused) 3 else 1) * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        view.animate().scaleX(if (focused) 1.03f else 1f).scaleY(if (focused) 1.03f else 1f)
                            .translationZ(if (focused) 10f else 0f).setDuration(120).start()
                    }
                }
        }
    }

    private fun showMobileMenu(anchor: View) {
        if (store.selected() == null) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Reload content")
            menu.add(0, 2, 1, "Change playlist")
            menu.add(0, 3, 2, "Account")
            menu.add(0, 4, 3, "Settings")
            menu.add(0, 5, 4, "Exit")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> if (section == Section.HOME) refreshAllCatalogs() else load(true)
                    2 -> showPlaylists()
                    3 -> showAccount()
                    4 -> showSettings()
                    5 -> confirmExit()
                }
                true
            }
            show()
        }
    }

    private fun configureSearch() {
        binding.searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                searchShouldFocusResults = true
                search(binding.searchBox.text.toString())
                hideKeyboard(clearFocus = false)
                true
            } else false
        }
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (section != Section.SEARCH) return
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(350); search(s?.toString().orEmpty()) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun hideKeyboard(clearFocus: Boolean = true) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)
        if (clearFocus) binding.searchBox.clearFocus()
    }

    private fun open(value: Section) {
        if (store.selected() == null) {
            showWelcome()
            return
        }
        ensurePlaylistState()
        captureSectionState()
        detailJob?.cancel()
        detailJob = null
        nestedSeries = null
        stateRetry = null
        loadJob?.cancel()
        if (value != Section.LIVE) {
            healthJob?.cancel()
            liveRankingJob?.cancel()
        }
        val navigationStarted = SystemClock.elapsedRealtime()
        val enteringAuthenticatedShell = binding.loginPanel.root.isVisible
        showAuthenticatedShell()
        section = value
        binding.pageProgress.isVisible = false
        val state = sectionState(value)
        currentCategory = state.categoryId
        val searchVisible = value == Section.SEARCH
        binding.searchRow.isVisible = searchVisible
        val television = deviceClass() == DeviceClass.TELEVISION
        binding.actionMore.isVisible = !television && !searchVisible
        binding.actionReload.isVisible = !television
        binding.actionPlaylist.isVisible = !television
        binding.categoryList.isVisible = value != Section.HOME && value != Section.SEARCH
        binding.screenTitle.text = when (value) {
            Section.HOME -> "Welcome to Crown Media"
            Section.LIVE -> "Live TV"
            Section.MOVIES -> "Movies"
            Section.SERIES -> "Series"
            Section.SEARCH -> "Search"
        }
        binding.screenSubtitle.text = store.selected()?.name ?: "Your world. One screen."
        updateSelectedNavigation(value)
        configureTvPresentation(value)
        if (value == Section.SEARCH) {
            val query = binding.searchBox.text.toString()
            if (query.trim().length >= 2) search(query)
            else {
                catalogAdapter.submit(emptyList())
                showState("Find anything", "Search live channels, movies, and series from your current playlist.", false, false)
            }
            binding.searchBox.requestFocus()
            binding.searchBox.post {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(binding.searchBox, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (value == Section.HOME) {
            showHome()
        } else if (state.cards.isNotEmpty()) {
            renderSectionState(state, restoreScroll = true)
            startCatalogRefresh(store.selected() ?: return, value, force = false, hadContent = true)
        } else {
            load()
        }
        if (BuildConfig.DEBUG) Log.d("CrownPerformance", "tab_${value.name.lowercase()}_render_ms=${SystemClock.elapsedRealtime() - navigationStarted}")
        if (enteringAuthenticatedShell && deviceClass() == DeviceClass.TELEVISION) {
            navigationView(value).post { navigationView(value).requestFocus() }
        }
    }

    private fun updateSelectedNavigation(value: Section) {
        binding.navHome.isSelected = value == Section.HOME
        binding.navLive.isSelected = value == Section.LIVE
        binding.navMovies.isSelected = value == Section.MOVIES
        binding.navSeries.isSelected = value == Section.SERIES
        binding.navSearch.isSelected = value == Section.SEARCH
        binding.sideNav.contentDescription = getString(R.string.selected_destination, value.displayName)
    }

    private fun configureTvPresentation(value: Section) {
        if (deviceClass() != DeviceClass.TELEVISION) return
        contentLayoutManager.spanCount = if (value == Section.HOME) 3 else 5
        catalogAdapter.setUniformLandscapeCards(value == Section.HOME || value == Section.SEARCH)
        val activeNav = navigationView(value)
        val primaryTarget = when (value) {
            Section.HOME -> binding.contentGrid
            Section.SEARCH -> binding.searchBox
            else -> binding.categoryList
        }
        listOf(binding.navHome, binding.navLive, binding.navMovies, binding.navSeries, binding.navSearch).forEach {
            it.nextFocusRightId = primaryTarget.id
        }
        binding.categoryList.nextFocusLeftId = activeNav.id
        binding.categoryList.nextFocusDownId = binding.contentGrid.id
        binding.contentGrid.nextFocusLeftId = activeNav.id
        binding.contentGrid.nextFocusUpId = if (value in PAGED_SECTIONS) binding.categoryList.id else binding.topBar.id
        binding.searchBox.nextFocusLeftId = binding.navSearch.id
        binding.searchBox.nextFocusDownId = binding.contentGrid.id
        binding.actionSearchClear.nextFocusLeftId = binding.searchBox.id
        binding.actionSearchClear.nextFocusDownId = binding.contentGrid.id
        binding.stateAction.nextFocusLeftId = activeNav.id
    }

    private fun navigationView(value: Section): View = when (value) {
        Section.HOME -> binding.navHome
        Section.LIVE -> binding.navLive
        Section.MOVIES -> binding.navMovies
        Section.SERIES -> binding.navSeries
        Section.SEARCH -> binding.navSearch
    }

    private fun load(force: Boolean = false) {
        val playlist = store.selected() ?: run { showWelcome(); return }
        if (section == Section.HOME) { showHome(); return }
        if (section == Section.SEARCH) { search(binding.searchBox.text.toString()); return }
        val target = section
        val state = sectionState(target)
        if (force && state.cards.isNotEmpty()) {
            binding.screenSubtitle.text = getString(R.string.updating_in_background, playlist.name)
            startCatalogRefresh(playlist, target, force = true, hadContent = true)
            return
        }
        loadJob?.cancel()
        if (state.cards.isEmpty()) showState("Loading", "", true, false)
        loadJob = lifecycleScope.launch {
            val cacheStarted = SystemClock.elapsedRealtime()
            val cachedResult = runCatching {
                val categories = cache.categories(playlist.id, target.cardKind)
                val page = cachedPage(playlist, target, state.categoryId, 0)
                categories to page
            }
            if (section != target) return@launch
            val (providerCategories, page) = cachedResult.getOrNull() ?: (emptyList<XtreamCategory>() to CatalogPage.EMPTY)
            state.categories = baseCategories(providerCategories, playlist.id)
            state.cards = page.cards
            state.nextOffset = page.consumed
            state.endReached = page.endReached
            state.categoryId = currentCategory
            lastCategories = state.categories
            if (state.cards.isNotEmpty()) {
                renderSectionState(state, restoreScroll = false)
                if (BuildConfig.DEBUG) Log.d("CrownPerformance", "${target.name.lowercase()}_cache_first_page_ms=${SystemClock.elapsedRealtime() - cacheStarted}")
                binding.screenSubtitle.text = getString(R.string.updating_in_background, playlist.name)
                if (target == Section.LIVE) {
                    scheduleLiveHealthRefresh(playlist, state.cards)
                    refreshLiveCategoryRanking(playlist)
                }
            }
            startCatalogRefresh(playlist, target, force, hadContent = state.cards.isNotEmpty())
        }
    }

    private fun selectCategory(category: XtreamCategory) {
        if (category.id.startsWith("season:")) {
            selectSeriesSeason(category.id.substringAfter(':').toIntOrNull() ?: return)
            return
        }
        if (requiresPin(category.name)) { verifyPin { selectCategory(category) }; return }
        if (category.id == currentCategory) return
        sectionState(section).apply {
            scrollState = null
            cards = emptyList()
            nextOffset = 0
            endReached = false
            categoryId = category.id
        }
        currentCategory = category.id
        categoriesAdapter.submit(lastCategories, currentCategory)
        load()
    }

    private fun ensurePlaylistState() {
        val playlistId = store.selected()?.id ?: return
        if (activePlaylistId == playlistId) return
        loadJob?.cancel()
        refreshJobs.values.forEach(Job::cancel)
        refreshJobs.clear()
        playlistRefreshJob?.cancel()
        healthJob?.cancel()
        liveRankingJob?.cancel()
        sectionStates.clear()
        activePlaylistId = playlistId
    }

    private fun sectionState(value: Section): SectionState =
        sectionStates.getOrPut(value) { SectionState() }

    private fun captureSectionState() {
        if (nestedSeries != null) return
        if (section !in PAGED_SECTIONS && section != Section.HOME) return
        sectionState(section).apply {
            categoryId = currentCategory
            cards = catalogAdapter.currentItems.toList()
            scrollState = binding.contentGrid.layoutManager?.onSaveInstanceState()
            categoryScrollState = binding.categoryList.layoutManager?.onSaveInstanceState()
            focusedCardKey = focusedCardKey()
        }
    }

    private fun renderSectionState(state: SectionState, restoreScroll: Boolean) {
        lastCategories = state.categories
        categoriesAdapter.submit(state.categories, state.categoryId) {
            if (restoreScroll) state.categoryScrollState?.let { binding.categoryList.layoutManager?.onRestoreInstanceState(it) }
        }
        hideState()
        catalogAdapter.submit(state.cards) {
            if (restoreScroll) state.scrollState?.let { binding.contentGrid.layoutManager?.onRestoreInstanceState(it) }
            val focusKey = pendingContentFocusKey ?: if (binding.contentGrid.hasFocus()) focusedCardKey() else null
            pendingContentFocusKey = null
            if (focusKey != null && deviceClass() == DeviceClass.TELEVISION) {
                restoreCardFocus(focusKey)
            }
        }
        if (section in PAGED_SECTIONS && deviceClass() == DeviceClass.TELEVISION) {
            binding.screenSubtitle.text = "${store.selected()?.name.orEmpty()}  •  ${getString(R.string.tv_content_hint)}"
        }
    }

    private fun focusedCardKey(): String? {
        val child = binding.contentGrid.focusedChild ?: return null
        val position = binding.contentGrid.getChildAdapterPosition(child)
        return catalogAdapter.itemAt(position)?.let { "${it.kind}:${it.id}" }
    }

    private fun restoreCardFocus(key: String?) {
        if (catalogAdapter.itemCount == 0) return
        val position = key?.let { requested ->
            catalogAdapter.currentItems.indexOfFirst { "${it.kind}:${it.id}" == requested }.takeIf { it >= 0 }
        } ?: 0
        binding.contentGrid.scrollToPosition(position)
        binding.contentGrid.post {
            binding.contentGrid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                ?: binding.contentGrid.requestFocus()
        }
    }

    private fun baseCategories(providerCategories: List<XtreamCategory>, playlistId: String): List<XtreamCategory> {
        val hidden = store.hiddenCategories(playlistId)
        return listOf(XtreamCategory("all", "All"), XtreamCategory("favorites", "Favorites")) +
            providerCategories.filterNot { it.id in hidden }
    }

    private suspend fun cachedPage(
        playlist: SavedPlaylist,
        target: Section,
        categoryId: String,
        offset: Int,
        limit: Int = PAGE_SIZE,
    ): CatalogPage {
        val actualCategory = categoryId.takeUnless { it == "all" || it == "favorites" }
        val favorites = store.favorites(playlist.id)
        val favoriteIds = favorites.asSequence()
            .mapNotNull { key -> key.substringAfter(':').takeIf { key.startsWith("${target.cardKind}:") } }
            .toList()
        val cards = mutableListOf<CatalogCard>()
        var consumed = 0
        var endReached = false
        while (cards.size < limit && !endReached) {
            val requested = limit - cards.size
            val raw = if (categoryId == "favorites") {
                cache.favoriteItemPage(playlist.id, target.cardKind, favoriteIds, requested, offset + consumed, store.sort)
            } else {
                cache.itemPage(playlist.id, target.cardKind, actualCategory, requested, offset + consumed, store.sort)
            }
            consumed += raw.size
            endReached = raw.size < requested
            if (raw.isEmpty()) break
            val usable = withContext(Dispatchers.Default) {
                val mapped = raw.map { it.toCard(target.cardKind, favorites) }
                val parentalSafe = if (store.parentalPin != null && !adultSessionUnlocked()) mapped.filterNot { it.isAdult } else mapped
                // Health affects rank only. Every stream remains directly playable, including
                // unknown and repeatedly failed content, because provider failures can recover.
                if (target == Section.LIVE) sort(parentalSafe, target) else parentalSafe
            }
            cards += usable
        }
        return CatalogPage(cards, consumed, endReached)
    }

    private fun loadNextPage() {
        val target = section
        if (target !in PAGED_SECTIONS) return
        val state = sectionState(target)
        if (state.loadingPage || state.endReached) return
        val playlist = store.selected() ?: return
        val categoryId = state.categoryId
        state.loadingPage = true
        binding.pageProgress.isVisible = true
        lifecycleScope.launch {
            val page = runCatching { cachedPage(playlist, target, categoryId, state.nextOffset) }.getOrNull()
            state.loadingPage = false
            if (section == target) binding.pageProgress.isVisible = false
            if (page == null || state.categoryId != categoryId) return@launch
            state.nextOffset += page.consumed
            state.endReached = page.endReached
            if (page.cards.isNotEmpty()) {
                val known = state.cards.asSequence().mapTo(HashSet()) { "${it.kind}:${it.id}" }
                state.cards = state.cards + page.cards.filter { known.add("${it.kind}:${it.id}") }
                if (section == target && currentCategory == categoryId) catalogAdapter.submit(state.cards)
            }
        }
    }

    private fun startCatalogRefresh(
        playlist: SavedPlaylist,
        target: Section,
        force: Boolean,
        hadContent: Boolean,
    ) {
        if (target !in PAGED_SECTIONS) return
        val state = sectionState(target)
        if (playlistRefreshJob?.isActive == true) {
            if (hadContent) return
            playlistRefreshJob?.cancel()
        }
        val persistedRefreshAt = store.catalogRefreshAt(playlist.id, target.cardKind, state.categoryId.takeUnless { it == "all" || it == "favorites" })
        state.lastRefreshAt = maxOf(state.lastRefreshAt, persistedRefreshAt)
        if (!force && hadContent && System.currentTimeMillis() - state.lastRefreshAt < REFRESH_TTL_MS) return
        if (refreshJobs[target]?.isActive == true) return
        if (!hadContent) {
            refreshJobs.filterKeys { it != target }.values.forEach(Job::cancel)
            healthJob?.cancel()
        }
        val refreshCategory = state.categoryId
        val job = lifecycleScope.launch {
            try {
                catalogRefreshPermit.withPermit {
                    refreshCatalog(playlist, target, refreshCategory, hadContent)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Throwable) {
                if (section == target && state.cards.isEmpty()) showFailure(error)
                else if (section == target) binding.screenSubtitle.text = getString(R.string.offline_catalog, playlist.name)
            }
        }
        refreshJobs[target] = job
        job.invokeOnCompletion { if (refreshJobs[target] === job) refreshJobs.remove(target) }
    }

    private suspend fun refreshCatalog(
        playlist: SavedPlaylist,
        target: Section,
        refreshCategory: String,
        hadContent: Boolean,
    ) {
        val refreshStarted = SystemClock.elapsedRealtime()
        val providerCategories = api.categories(playlist.credentials, target.apiKind)
        cache.saveCategories(playlist.id, target.cardKind, providerCategories)
        val state = sectionState(target)
        state.categories = baseCategories(providerCategories, playlist.id)
        val actualCategory = refreshCategory.takeUnless { it == "all" || it == "favorites" }
        val refreshMarker = System.currentTimeMillis()
        val favorites = store.favorites(playlist.id)
        var received = 0
        var firstBatchRendered = hadContent
        api.catalogBatches(
            playlist.credentials,
            target.cardKind,
            actualCategory,
            NETWORK_BATCH_SIZE,
        ).collect { batch ->
            cache.saveItemBatch(playlist.id, target.cardKind, refreshMarker, batch, received)
            received += batch.size
            if (!firstBatchRendered && state.categoryId == refreshCategory) {
                val firstCards = withContext(Dispatchers.Default) {
                    val mapped = batch.map { it.toCard(target.cardKind, favorites) }
                    val selected = if (refreshCategory == "favorites") mapped.filter {
                        "${it.kind}:${it.id}" in favorites
                    } else mapped
                    val parentalSafe = if (store.parentalPin != null && !adultSessionUnlocked()) selected.filterNot { it.isAdult } else selected
                    (if (target == Section.LIVE) sort(parentalSafe, target) else parentalSafe).take(PAGE_SIZE)
                }
                if (firstCards.isNotEmpty()) {
                    state.cards = firstCards
                    state.nextOffset = if (refreshCategory == "favorites") firstCards.size else received
                    state.endReached = false
                    if (section == target && currentCategory == refreshCategory) {
                        lastCategories = state.categories
                        renderSectionState(state, restoreScroll = false)
                        binding.screenSubtitle.text = getString(R.string.updating_in_background, playlist.name)
                    }
                    if (BuildConfig.DEBUG) Log.d("CrownPerformance", "${target.name.lowercase()}_network_first_page_ms=${SystemClock.elapsedRealtime() - refreshStarted}")
                    firstBatchRendered = true
                }
            }
        }
        cache.finishItemRefresh(playlist.id, target.cardKind, actualCategory, refreshMarker, received)
        state.lastRefreshAt = System.currentTimeMillis()
        if (received > 0) store.markCatalogRefreshed(playlist.id, target.cardKind, actualCategory, state.lastRefreshAt)
        if (BuildConfig.DEBUG) Log.d("CrownPerformance", "${target.name.lowercase()}_refresh_total_ms=${SystemClock.elapsedRealtime() - refreshStarted};items=$received")
        if (state.categoryId == refreshCategory) {
            val visibleCount = state.cards.size.coerceAtLeast(PAGE_SIZE)
            val refreshed = cachedPage(playlist, target, refreshCategory, 0, visibleCount)
            state.cards = refreshed.cards
            state.nextOffset = refreshed.consumed
            state.endReached = refreshed.endReached
        }
        if (section == target) {
            currentCategory = state.categoryId
            lastCategories = state.categories
            if (state.cards.isEmpty()) {
                stateRetry = { binding.categoryList.requestFocus() }
                showState(
                    "Nothing here",
                    "This category does not contain any available content. Choose another category to continue.",
                    false,
                    true,
                    getString(R.string.choose_another_category),
                    keepCategories = true,
                )
            } else {
                renderSectionState(state, restoreScroll = hadContent)
                binding.screenSubtitle.text = playlist.name
                if (target == Section.LIVE) {
                    scheduleLiveHealthRefresh(playlist, state.cards)
                    refreshLiveCategoryRanking(playlist)
                }
            }
        }
    }

    private fun refreshAllCatalogs() {
        val playlist = store.selected() ?: return
        if (playlistRefreshJob?.isActive == true) return
        binding.screenSubtitle.text = getString(R.string.updating_in_background, playlist.name)
        playlistRefreshJob = lifecycleScope.launch {
            var failures = 0
            for (target in PAGED_SECTIONS) {
                val state = sectionState(target)
                try {
                    catalogRefreshPermit.withPermit {
                        refreshCatalog(playlist, target, state.categoryId, hadContent = true)
                    }
                } catch (_: CancellationException) {
                    return@launch
                } catch (_: Throwable) {
                    failures++
                }
            }
            if (section == Section.HOME) {
                binding.screenSubtitle.text = playlist.name
                Toast.makeText(
                    this@MainActivity,
                    if (failures == 0) "Content updated" else "Update failed for $failures section(s). Showing saved content.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun hideCategory(category: XtreamCategory) {
        if (category.id == "all" || category.id == "favorites") return
        val playlist = store.selected() ?: return
        AlertDialog.Builder(this).setTitle("Hide ${category.name}?")
            .setMessage("You can restore hidden categories from Settings.")
            .setPositiveButton("Hide") { _, _ ->
                store.setHiddenCategories(playlist.id, store.hiddenCategories(playlist.id) + category.id)
                currentCategory = "all"
                sectionState(section).apply {
                    categoryId = "all"; cards = emptyList(); nextOffset = 0; endReached = false; scrollState = null
                }
                load()
            }.setNegativeButton("Cancel", null).showCrown()
    }

    private fun showHome() {
        hideState()
        categoriesAdapter.submit(emptyList())
        val p = store.selected() ?: return
        val expiry = p.expiresAt?.let { DateFormat.getDateInstance().format(Date(it * 1000)) } ?: "No expiry reported"
        val cards = listOf(
            CatalogCard("live", "home", "Live TV", null, "Channels, EPG & catch-up", "WATCH", localArtwork = R.drawable.home_live),
            CatalogCard("movies", "home", "Movies", null, "Details, trailers & resume", "EXPLORE", localArtwork = R.drawable.home_movies),
            CatalogCard("series", "home", "Series", null, "Seasons, episodes & tracks", "BINGE", localArtwork = R.drawable.home_series),
            CatalogCard("account", "home", "${p.name} account", null, "${p.status} • $expiry", "ACTIVE", localArtwork = R.drawable.home_account),
            CatalogCard("reload", "home", "Reload content", null, "Refresh provider catalogs", "SYNC", localArtwork = R.drawable.home_reload),
            CatalogCard("playlist", "home", "Change playlist", null, "${store.playlists().size} playlist(s)", "SWITCH", localArtwork = R.drawable.home_playlist),
        )
        val state = sectionState(Section.HOME).apply { this.cards = cards; categories = emptyList() }
        catalogAdapter.submit(cards) {
            state.scrollState?.let { binding.contentGrid.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun openCard(card: CatalogCard) {
        if (card.isAdult && store.parentalPin != null && !adultSessionUnlocked()) {
            verifyPin { openCardUnlocked(card) }
            return
        }
        openCardUnlocked(card)
    }

    private fun openCardUnlocked(card: CatalogCard) {
        if (card.kind == "home") {
            when (card.id) {
                "live" -> open(Section.LIVE); "movies" -> open(Section.MOVIES); "series" -> open(Section.SERIES)
                "account" -> showAccount(); "reload" -> refreshAllCatalogs(); "playlist" -> showPlaylists()
            }; return
        }
        when (card.kind) {
            "live" -> play(card, live = true)
            "movie" -> showMovie(card)
            "series" -> showSeries(card)
            "episode" -> play(card, live = false)
        }
    }

    private fun cardOptions(card: CatalogCard) {
        if (card.kind == "home") return
        val playlist = store.selected() ?: return
        val actions = if (card.kind == "live") arrayOf("Play", "Programme guide", "Favorite / unfavorite") else arrayOf("Details / play", "Favorite / unfavorite")
        AlertDialog.Builder(this).setTitle(card.title).setItems(actions) { _, which ->
            when {
                which == 0 -> openCard(card)
                card.kind == "live" && which == 1 -> showEpg(card)
                else -> { store.toggleFavorite(playlist.id, "${card.kind}:${card.id}"); load() }
            }
        }.showCrown(preferredButton = null)
    }

    private fun play(card: CatalogCard, live: Boolean) {
        val playlist = store.selected() ?: return
        healthJob?.cancel()
        liveRankingJob?.cancel()
        refreshJobs.values.forEach { it.cancel() }
        playlistRefreshJob?.cancel()
        searchWarmJob?.cancel()
        val extension = if (live) preferredLiveExtension(playlist.allowedFormats) else card.extension
        val url = api.streamUrl(playlist.credentials, card.kind, card.id, extension)
        val fallbackUrl = if (live) {
            val allowed = playlist.allowedFormats.map { it.trim().trimStart('.').lowercase() }
            val fallbackExtension = when (extension?.lowercase()) {
                "m3u8" -> "ts".takeIf { it in allowed }
                "ts" -> "m3u8".takeIf { it in allowed }
                else -> null
            }
            fallbackExtension?.let { api.streamUrl(playlist.credentials, card.kind, card.id, it) }
        } else null
        when (store.player) {
            "vlc" -> external(url, card.title, "org.videolan.vlc")
            "mx" -> if (!PlayerActivity.launchExternal(this, url, card.title, "com.mxtech.videoplayer.ad")) external(url, card.title, "com.mxtech.videoplayer.pro")
            "chooser" -> external(url, card.title, null)
            else -> internalPlayer.launch(
                PlayerActivity.internalIntent(
                    this,
                    url,
                    card.title,
                    live,
                    store.buffer,
                    playlist.id,
                    card.id,
                    card.kind,
                    fallbackUrl,
                ),
            )
        }
    }

    private fun external(url: String, title: String, packageName: String?) {
        if (!PlayerActivity.launchExternal(this, url, title, packageName)) {
            Toast.makeText(this, "Selected external player is not installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMovie(card: CatalogCard) {
        val playlist = store.selected() ?: return
        showState("Loading movie", card.title, true, false)
        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            runCatching { api.movieInfo(playlist.credentials, card.id) }.onSuccess { movie ->
                if (section != Section.MOVIES || store.selected()?.id != playlist.id) return@onSuccess
                hideState()
                val message = listOfNotNull(
                    listOfNotNull(movie.year, movie.rating?.let { "★ $it" }, movie.duration, movie.genre).joinToString("  •  ").takeIf(String::isNotBlank),
                    movie.cast?.let { "Cast\n$it" },
                    movie.plot,
                ).joinToString("\n\n")
                val builder = AlertDialog.Builder(this@MainActivity).setTitle(movie.name)
                if (deviceClass() == DeviceClass.TELEVISION) builder.setView(contentDetailsView(card, message))
                else builder.setMessage(message.ifBlank { "No metadata supplied." })
                builder
                    .setPositiveButton("Play") { _, _ -> play(card.copy(extension = movie.extension), false) }
                    .setNeutralButton(if (movie.trailer.isNullOrBlank()) "Favorite" else "Trailer") { _, _ ->
                        movie.trailer?.takeIf { it.isNotBlank() }?.let(::openTrailer) ?: store.toggleFavorite(playlist.id, "movie:${card.id}")
                    }.setNegativeButton("Close", null).showCrown()
            }.onFailure { error ->
                if (error !is CancellationException && section == Section.MOVIES) showOperationFailure(error) { showMovie(card) }
            }
        }
    }

    private fun showSeries(card: CatalogCard) {
        val playlist = store.selected() ?: return
        showState("Loading series", card.title, true, false)
        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            runCatching { api.seriesInfo(playlist.credentials, card.id) }.onSuccess { details ->
                if (section != Section.SERIES || store.selected()?.id != playlist.id) return@onSuccess
                hideState()
                if (details.episodes.values.all { it.isEmpty() }) {
                    val builder = AlertDialog.Builder(this@MainActivity).setTitle(details.name)
                    if (deviceClass() == DeviceClass.TELEVISION) builder.setView(contentDetailsView(card, details.plot ?: "No episodes supplied."))
                    else builder.setMessage(details.plot ?: "No episodes supplied.")
                    builder.setPositiveButton("Close", null).showCrown()
                } else {
                    val openEpisodes = {
                        nestedSeries = SeriesDetailState(card, details, details.episodes.keys.minOrNull() ?: 1)
                        renderSeriesDetails()
                    }
                    if (deviceClass() == DeviceClass.TELEVISION) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(details.name)
                            .setView(contentDetailsView(card, listOfNotNull(details.genre, details.plot).joinToString("\n\n")))
                            .setPositiveButton("Browse episodes") { _, _ -> openEpisodes() }
                            .setNegativeButton("Back", null)
                            .showCrown()
                    } else openEpisodes()
                }
            }.onFailure { error ->
                if (error !is CancellationException && section == Section.SERIES) showOperationFailure(error) { showSeries(card) }
            }
        }
    }

    private fun renderSeriesDetails() {
        val nested = nestedSeries ?: return
        val seasons = nested.details.episodes.keys.sorted().map { XtreamCategory("season:$it", "Season $it") }
        val episodes = nested.details.episodes[nested.season].orEmpty().sortedBy { it.episodeNumber }.map { episode ->
            val meta = listOf("S${nested.season} E${episode.episodeNumber}", episode.duration).filterNotNull().filter(String::isNotBlank).joinToString(" • ")
            CatalogCard(episode.id, "episode", episode.title, episode.imageUrl, meta, "S${nested.season}", episode.extension)
        }
        binding.screenTitle.text = nested.details.name
        binding.screenSubtitle.text = listOfNotNull(nested.details.genre, "Season ${nested.season}", "Back returns to Series").joinToString("  •  ")
        binding.categoryList.isVisible = true
        categoriesAdapter.submit(seasons, "season:${nested.season}")
        hideState()
        catalogAdapter.submit(episodes) { if (deviceClass() == DeviceClass.TELEVISION && episodes.isNotEmpty()) restoreCardFocus(null) }
        if (episodes.isEmpty()) {
            stateRetry = { closeSeriesDetails() }
            showState(
                "No episodes",
                "This season does not contain any episodes. Choose another season above or return to Series.",
                false,
                true,
                getString(R.string.back_to_series),
                keepCategories = true,
            )
        }
    }

    private fun selectSeriesSeason(season: Int) {
        val nested = nestedSeries ?: return
        nested.season = season
        renderSeriesDetails()
    }

    private fun closeSeriesDetails() {
        nestedSeries = null
        detailJob?.cancel()
        detailJob = null
        binding.screenTitle.text = "Series"
        binding.screenSubtitle.text = store.selected()?.name.orEmpty()
        renderSectionState(sectionState(Section.SERIES), restoreScroll = true)
    }

    private fun showEpg(card: CatalogCard) {
        val playlist = store.selected() ?: return
        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            runCatching { api.shortEpg(playlist.credentials, card.id) }.onSuccess { entries ->
                if (section != Section.LIVE || store.selected()?.id != playlist.id) return@onSuccess
                val labels = entries.map { "${it.start.orEmpty()}  ${it.title}\n${it.description.orEmpty()}" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("${card.title} programme guide")
                    .setItems(if (labels.isEmpty()) arrayOf("No EPG supplied") else labels) { _, which ->
                        val entry = entries.getOrNull(which) ?: return@setItems
                        val startTimestamp = entry.startTimestamp
                        val stopTimestamp = entry.stopTimestamp
                        if (card.badge.contains("CATCH", true) && startTimestamp != null && stopTimestamp != null) {
                            val duration = ((stopTimestamp - startTimestamp) / 60).toInt().coerceAtLeast(1)
                            val start = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).format(Date(startTimestamp * 1000))
                            val url = api.catchUpUrl(playlist.credentials, card.id, start, duration)
                            startActivity(PlayerActivity.internalIntent(this@MainActivity, url, entry.title, false, store.buffer))
                        }
                    }.setNegativeButton("Close", null).showCrown()
            }.onFailure { error ->
                if (error !is CancellationException && section == Section.LIVE) showOperationFailure(error) { showEpg(card) }
            }
        }
    }

    private fun contentDetailsView(card: CatalogCard, body: String): View {
        val view = layoutInflater.inflate(R.layout.dialog_content_details, FrameLayout(this), false)
        view.findViewById<TextView>(R.id.detail_body).text = body.ifBlank { "No metadata supplied." }
        val artwork = view.findViewById<ImageView>(R.id.detail_artwork)
        val source = card.preferredArtworkSource()
        val inset = (12 * resources.displayMetrics.density).toInt()
        val showBrand = {
            artwork.scaleType = ImageView.ScaleType.FIT_CENTER
            artwork.setPadding(inset, inset, inset, inset)
        }
        showBrand()
        artwork.load(source) {
            placeholder(R.drawable.crown_media_logo_header)
            error(R.drawable.crown_media_logo_header)
            crossfade(false)
            listener(
                onSuccess = { _, _ ->
                    if (source == R.drawable.crown_media_logo_header) showBrand()
                    else {
                        artwork.scaleType = if (card.kind == "live") ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
                        artwork.setPadding(0, 0, 0, 0)
                    }
                },
                onError = { _, _ -> showBrand() },
            )
        }
        return view
    }

    private fun search(query: String) {
        if (query.trim().length < 2) {
            searchShouldFocusResults = false
            showState("Type at least 2 characters", "Results will include live TV, movies, and series.", false, false)
            binding.searchBox.requestFocus()
            return
        }
        val p = store.selected() ?: return
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val normalizedQuery = query.trim()
            var cached = runCatching { cache.search(p.id, normalizedQuery).map { (kind, item) ->
                item.toCard(kind, store.favorites(p.id))
            }.filterNot { it.isAdult && store.parentalPin != null && !adultSessionUnlocked() } }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                hideState()
                submitSearchResults(sort(cached))
                binding.screenSubtitle.text = getString(R.string.saved_results, p.name)
            } else {
                showState("Searching", "Looking across your saved catalog…", true, false)
            }

            if (searchWarmCompletedFor != p.id) {
                val missingKinds = listOf("live", "movie", "series").filter { !store.catalogComplete(p.id, it, null) }
                if (missingKinds.isNotEmpty()) {
                    val warm = searchWarmJob?.takeIf { it.isActive } ?: lifecycleScope.async {
                        warmSearchCatalogs(p, missingKinds) {
                            if (section == Section.SEARCH && binding.searchBox.text.toString().trim() == normalizedQuery) {
                                val partial = cache.search(p.id, normalizedQuery).map { (kind, item) -> item.toCard(kind, store.favorites(p.id)) }
                                    .filterNot { it.isAdult && store.parentalPin != null && !adultSessionUnlocked() }
                                hideState()
                                submitSearchResults(sort(partial))
                                binding.screenSubtitle.text = getString(R.string.saved_results, p.name)
                            }
                        }
                    }.also { searchWarmJob = it }
                    val result = warm.await()
                    if (result.isFailure && cached.isEmpty()) {
                        showFailure(result.exceptionOrNull() ?: IllegalStateException("Catalog sync failed"))
                        return@launch
                    }
                }
                searchWarmCompletedFor = p.id
                cached = cache.search(p.id, normalizedQuery).map { (kind, item) ->
                    item.toCard(kind, store.favorites(p.id))
                }.filterNot { it.isAdult && store.parentalPin != null && !adultSessionUnlocked() }
            }

            hideState()
            submitSearchResults(sort(cached))
            binding.screenSubtitle.text = p.name
            if (cached.isEmpty()) {
                searchShouldFocusResults = false
                stateRetry = { binding.searchBox.requestFocus() }
                showState("No results", "Try a different title or channel name.", false, true, "EDIT SEARCH")
            }
        }
    }

    private fun submitSearchResults(cards: List<CatalogCard>) {
        catalogAdapter.submit(cards) {
            if (searchShouldFocusResults && cards.isNotEmpty() && section == Section.SEARCH) {
                searchShouldFocusResults = false
                binding.searchBox.clearFocus()
                restoreCardFocus(null)
            }
        }
    }

    private suspend fun warmSearchCatalogs(
        playlist: SavedPlaylist,
        missingKinds: List<String>,
        onKindComplete: suspend () -> Unit = {},
    ): Result<Unit> = runCatching {
        for (kind in missingKinds) {
            val refreshMarker = System.currentTimeMillis()
            var received = 0
            api.catalogBatches(playlist.credentials, kind, batchSize = NETWORK_BATCH_SIZE).collect { batch ->
                cache.saveItemBatch(playlist.id, kind, refreshMarker, batch, received)
                received += batch.size
            }
            cache.finishItemRefresh(playlist.id, kind, null, refreshMarker, received)
            if (received > 0) store.markCatalogRefreshed(playlist.id, kind, null)
            onKindComplete()
        }
        Unit
    }

    private fun configureLogin() = with(binding.loginPanel) {
        serviceDropdown.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, CrownService.displayNames))
        serviceDropdown.setText(CrownService.default.displayName, false)
        loginService = CrownService.default
        serviceDropdown.setOnItemClickListener { _, _, _, _ ->
            val selected = CrownService.fromDisplayName(serviceDropdown.text.toString())
            if (selected != loginService) {
                loginService = selected
                restoreSavedLoginDetails(selected)
            }
            updateSelectedService()
        }
        connectButton.setOnClickListener { connectPlaylist() }
        qrButton.setOnClickListener { showDeviceActivation() }
        qrButton.isVisible = QR_CONNECT_UI_ENABLED
        qrButton.isEnabled = QR_CONNECT_UI_ENABLED && BuildConfig.ACTIVATION_BASE_URL.isNotBlank()
        connectButton.nextFocusDownId = if (QR_CONNECT_UI_ENABLED && BuildConfig.ACTIVATION_BASE_URL.isNotBlank()) qrButton.id else View.NO_ID
        if (BuildConfig.ACTIVATION_BASE_URL.isBlank()) qrButton.text = getString(R.string.connect_via_qr_unavailable)
        loginCancel.setOnClickListener { cancelLogin(); open(Section.HOME) }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connectPlaylist()
                true
            } else false
        }
        saveLogin.setOnCheckedChangeListener { _, checked ->
            if (!updatingLoginForm && !checked) store.saveLoginDetails(loginService, null)
        }
        restoreSavedLoginDetails(loginService)
        updateSelectedService()
    }

    private fun restoreSavedLoginDetails(service: CrownService) = with(binding.loginPanel) {
        val saved = store.savedLoginDetails(service)
        updatingLoginForm = true
        playlistName.setText(saved?.playlistName.orEmpty())
        username.setText(saved?.username.orEmpty())
        password.setText(saved?.password.orEmpty())
        saveLogin.isChecked = saved != null
        updatingLoginForm = false
    }

    private fun showWelcome() {
        catalogAdapter.submit(emptyList())
        showLogin(canGoBack = false)
    }

    private fun showManualPlaylist() {
        showLogin(canGoBack = store.selected() != null)
    }

    private fun showLogin(canGoBack: Boolean) = with(binding.loginPanel) {
        loginJob?.cancel()
        setLoginLoading(false)
        clearLoginErrors()
        binding.topBar.isVisible = false
        binding.actionMore.isVisible = false
        binding.sideNav.isVisible = false
        binding.searchRow.isVisible = false
        binding.categoryList.isVisible = false
        binding.contentGrid.isVisible = false
        binding.statePanel.isVisible = false
        root.isVisible = true
        loginCancel.isVisible = canGoBack
        loginCancel.isEnabled = canGoBack
        if (deviceClass() == DeviceClass.TELEVISION) serviceDropdown.requestFocus() else playlistName.requestFocus()
    }

    private fun showAuthenticatedShell() {
        binding.loginPanel.root.isVisible = false
        binding.topBar.isVisible = true
        binding.sideNav.isVisible = true
    }

    private fun updateSelectedService() = with(binding.loginPanel) {
        val service = CrownService.fromDisplayName(serviceDropdown.text.toString())
        loginService = service
        serviceLayout.error = if (service.isAvailable) null else getString(R.string.service_coming_soon)
        connectButton.isEnabled = service.isAvailable && loginJob?.isActive != true
        if (service.isAvailable && formError.text == getString(R.string.service_coming_soon)) formError.isVisible = false
    }

    private fun connectPlaylist(): Unit = with(binding.loginPanel) {
        if (loginJob?.isActive == true) return
        clearLoginErrors()
        val service = CrownService.fromDisplayName(serviceDropdown.text.toString())
        if (!service.isAvailable) {
            serviceLayout.error = getString(R.string.service_coming_soon)
            formError.text = getString(R.string.service_coming_soon)
            formError.isVisible = true
            serviceDropdown.requestFocus()
            return
        }
        val usernameValue = username.text?.toString().orEmpty().trim()
        val passwordValue = password.text?.toString().orEmpty()
        val firstInvalid: com.google.android.material.textfield.TextInputLayout? = when {
            usernameValue.isBlank() -> usernameLayout.apply { error = "Username is required" }
            passwordValue.isBlank() -> passwordLayout.apply { error = "Password is required" }
            else -> null
        }
        if (firstInvalid != null) {
            firstInvalid.editText?.requestFocus()
            return
        }

        val name = playlistName.text?.toString().orEmpty().trim().ifBlank { service.displayName }
        val credentials = ProviderCredentials(requireNotNull(service.serverUrl), usernameValue, passwordValue)
        setLoginLoading(true)
        val generation = ++loginGeneration
        loginJob = lifecycleScope.launch {
            runCatching { api.authenticate(credentials) }.onSuccess { account ->
                if (generation != loginGeneration || !binding.loginPanel.root.isVisible) return@onSuccess
                if (account.status.name != "ACTIVE") {
                    setLoginLoading(false)
                    formError.text = getString(R.string.inactive_account, account.status.name.lowercase())
                    formError.isVisible = true
                } else {
                    if (saveLogin.isChecked) {
                        store.saveLoginDetails(
                            service,
                            SavedLoginDetails(playlistName.text?.toString().orEmpty().trim(), service, usernameValue, passwordValue),
                        )
                    } else {
                        store.saveLoginDetails(service, null)
                    }
                    store.save(
                        name, credentials, account.expiresAtEpochSeconds, account.status.name,
                        account.activeConnections, account.maximumConnections,
                        allowedFormats = account.allowedFormats,
                        persist = saveLogin.isChecked,
                    )
                    setLoginLoading(false)
                    open(Section.HOME)
                }
            }.onFailure { error ->
                if (error is CancellationException || generation != loginGeneration) return@onFailure
                setLoginLoading(false)
                formError.text = friendly(error)
                formError.isVisible = true
            }
        }
    }

    private fun cancelLogin() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        if (::binding.isInitialized) setLoginLoading(false)
    }

    private fun clearLoginErrors() = with(binding.loginPanel) {
        serviceLayout.error = null
        usernameLayout.error = null
        passwordLayout.error = null
        formError.text = ""
        formError.isVisible = false
    }

    private fun setLoginLoading(loading: Boolean) = with(binding.loginPanel) {
        playlistNameLayout.isEnabled = !loading
        serviceLayout.isEnabled = !loading
        usernameLayout.isEnabled = !loading
        passwordLayout.isEnabled = !loading
        saveLogin.isEnabled = !loading
        qrButton.isEnabled = QR_CONNECT_UI_ENABLED && !loading && BuildConfig.ACTIVATION_BASE_URL.isNotBlank()
        loginCancel.isEnabled = !loading && loginCancel.isVisible
        loginProgress.isVisible = loading
        connectStatus.isVisible = loading
        connectButton.isEnabled = !loading && CrownService.fromDisplayName(serviceDropdown.text.toString()).isAvailable
    }

    private fun showPlaylists() {
        val values = store.playlists()
        val builder = AlertDialog.Builder(this).setTitle("Change playlist")
            .setItems(values.map { "${if (it.id == store.selectedId) "✓ " else ""}${it.name}  •  ${it.status}" }.toTypedArray()) { _, index ->
                store.selectedId = values[index].id; open(Section.HOME)
            }.setPositiveButton("Add manually") { _, _ -> showManualPlaylist() }
            .setNegativeButton("Close", null)
        if (QR_CONNECT_UI_ENABLED && BuildConfig.ACTIVATION_BASE_URL.isNotBlank()) {
            builder.setNeutralButton("Device activation") { _, _ -> showDeviceActivation() }
        }
        builder.showCrown(preferredButton = null)
    }

    private fun showDeviceActivation() {
        if (BuildConfig.ACTIVATION_BASE_URL.isBlank()) {
            Toast.makeText(this, getString(R.string.activation_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        val key = deviceKey()
        val base = BuildConfig.ACTIVATION_BASE_URL.trimEnd('/')
        val activationUrl = if (base.isBlank()) "https://crownmedia.tv/activate?device=$key" else "$base/activate?device=$key"
        val density = resources.displayMetrics.density
        val maximumQrDp = if (deviceClass() == DeviceClass.TELEVISION) 360 else 300
        val qrDp = (resources.configuration.screenWidthDp - 96).coerceIn(220, maximumQrDp)
        val qrPx = (qrDp * density).toInt()
        Toast.makeText(this, "Preparing QR code…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { qr(activationUrl, qrPx) }
            val horizontalPadding = (20 * density).toInt()
            val layout = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding(horizontalPadding, (12 * density).toInt(), horizontalPadding, (8 * density).toInt()) }
            val image = ImageView(this@MainActivity).apply { setImageBitmap(bitmap); contentDescription = getString(R.string.activation_qr_description) }
            val text = TextView(this@MainActivity).apply { this.text = getString(R.string.activation_instructions, key); gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, (12 * density).toInt(), 0, 0) }
            layout.addView(image, LinearLayout.LayoutParams(qrPx, qrPx)); layout.addView(text)
            AlertDialog.Builder(this@MainActivity).setTitle("Connect via QR Code").setView(layout).setMessage(getString(R.string.activation_waiting))
                .setPositiveButton("Close", null).showCrown()
        }
    }

    private fun showAccount() {
        val p = store.selected() ?: run { showWelcome(); return }
        val expiry = p.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) } ?: "Not supplied"
        val dialog = AlertDialog.Builder(this).setTitle(p.name).setMessage(
            "STATUS\n${p.status}\n\nEXPIRY\n$expiry\n\nCONNECTIONS\n${p.activeConnections ?: "Not supplied"} active of ${p.maximumConnections ?: "Not supplied"} maximum\n\nSERVER\n${Uri.parse(p.credentials.serverUrl).host ?: "Configured"}\n\nUSERNAME\nSaved securely"
        ).setPositiveButton("Refresh account") { _, _ -> refreshAccount(p) }
            .setNeutralButton("Remove playlist") { _, _ -> confirmRemove(p) }
            .setNegativeButton("Close", null).showCrown()
        if (deviceClass() == DeviceClass.TELEVISION) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ContextCompat.getColor(this, R.color.crown_error))
        }
    }

    private fun refreshAccount(p: SavedPlaylist) {
        lifecycleScope.launch {
            runCatching { api.authenticate(p.credentials) }.onSuccess { a ->
                store.save(
                    p.name, p.credentials, a.expiresAtEpochSeconds, a.status.name,
                    a.activeConnections, a.maximumConnections, id = p.id,
                    allowedFormats = a.allowedFormats,
                )
                Toast.makeText(this@MainActivity, "Account refreshed", Toast.LENGTH_SHORT).show(); showAccount()
            }.onFailure { error ->
                if (error !is CancellationException) Toast.makeText(this@MainActivity, friendly(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPlaybackCapabilitiesIfNeeded() {
        val playlist = store.selected()?.takeIf { it.allowedFormats.isEmpty() } ?: return
        lifecycleScope.launch {
            runCatching { api.authenticate(playlist.credentials) }.onSuccess { account ->
                store.save(
                    playlist.name, playlist.credentials, account.expiresAtEpochSeconds, account.status.name,
                    account.activeConnections, account.maximumConnections, id = playlist.id,
                    allowedFormats = account.allowedFormats,
                )
            }
        }
    }

    private fun confirmRemove(p: SavedPlaylist) {
        AlertDialog.Builder(this).setTitle("Remove ${p.name}?").setMessage("Cached preferences and credentials for this playlist will be removed from this device.")
            .setPositiveButton("Remove") { _, _ -> lifecycleScope.launch {
                cache.deletePlaylist(p.id)
                store.remove(p.id)
                if (store.selected() == null) showWelcome() else open(Section.HOME)
            } }
            .setNegativeButton("Cancel", null).showCrown()
    }

    private fun showSettings() {
        val playerLabel = when (store.player) { "vlc" -> "VLC"; "mx" -> "MX Player"; "chooser" -> "System chooser"; else -> "Internal Crown Player" }
        val bufferLabel = when (store.buffer) { "low" -> "Low latency"; "resilient" -> "Resilient"; else -> "Normal" }
        val sortLabel = when (store.sort) { "asc" -> "Name A–Z"; "desc" -> "Name Z–A"; else -> "Provider order" }
        val labels = arrayOf(
            "Playback player  •  $playerLabel",
            "Buffer profile  •  $bufferLabel",
            "Content sorting  •  $sortLabel",
            "Parental controls  •  ${if (store.parentalPin == null) "Off" else "On"}",
            "Restore hidden categories",
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(labels) { _, which ->
            when (which) {
                0 -> showPlayerChoice()
                1 -> showBufferChoice()
                2 -> showSortChoice()
                3 -> setParentalPin()
                4 -> {
                    store.selected()?.let { store.setHiddenCategories(it.id, emptySet()) }
                    Toast.makeText(this, getString(R.string.settings_updated), Toast.LENGTH_SHORT).show()
                    showSettings()
                }
            }
        }.setNegativeButton("Close", null).showCrown(preferredButton = null)
    }

    private fun showPlayerChoice() {
        val labels = arrayOf("Internal Crown Player", "VLC", "MX Player", "System chooser")
        val values = arrayOf("internal", "vlc", "mx", "chooser")
        AlertDialog.Builder(this).setTitle("Playback player")
            .setSingleChoiceItems(labels, values.indexOf(store.player).coerceAtLeast(0)) { dialog, which ->
                store.player = values[which]
                dialog.dismiss()
                showSettings()
            }
            .setNegativeButton("Back", null)
            .showCrown(preferredButton = null)
    }

    private fun showBufferSettings() {
        val bufferLabel = when (store.buffer) { "low" -> "Low latency"; "resilient" -> "Resilient"; else -> "Normal" }
        val sortLabel = when (store.sort) { "asc" -> "Name A–Z"; "desc" -> "Name Z–A"; else -> "Provider order" }
        val labels = arrayOf("Buffer profile: $bufferLabel", "Content sorting: $sortLabel", "Restore hidden categories")
        AlertDialog.Builder(this).setTitle("Buffer and content").setItems(labels) { _, which ->
            when (which) {
                0 -> showBufferChoice()
                1 -> showSortChoice()
                2 -> store.selected()?.let { store.setHiddenCategories(it.id, emptySet()) }
            }
        }.setNegativeButton("Back", null).showCrown(preferredButton = null)
    }

    private fun showBufferChoice() {
        val labels = arrayOf("Low latency", "Normal", "Resilient")
        val values = arrayOf("low", "normal", "resilient")
        AlertDialog.Builder(this).setTitle("Buffer profile")
            .setSingleChoiceItems(labels, values.indexOf(store.buffer).coerceAtLeast(1)) { dialog, which ->
                store.buffer = values[which]
                dialog.dismiss()
                showSettings()
            }.setNegativeButton("Back", null).showCrown(preferredButton = null)
    }

    private fun showSortChoice() {
        val labels = arrayOf("Provider order", "Name A–Z", "Name Z–A")
        val values = arrayOf("provider", "asc", "desc")
        AlertDialog.Builder(this).setTitle("Content sorting")
            .setSingleChoiceItems(labels, values.indexOf(store.sort).coerceAtLeast(0)) { dialog, which ->
                store.sort = values[which]
                sectionStates.filterKeys { it in PAGED_SECTIONS }.values.forEach { state ->
                    state.cards = emptyList(); state.nextOffset = 0; state.endReached = false; state.scrollState = null
                }
                if (section in PAGED_SECTIONS) load()
                dialog.dismiss()
                showSettings()
            }.setNegativeButton("Back", null).showCrown(preferredButton = null)
    }

    private fun setParentalPin() {
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply { hint = "4–8 digit PIN"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; setPadding(horizontalPadding, 16, horizontalPadding, 16) }
        val dialog = AlertDialog.Builder(this).setTitle("Parental controls").setMessage("Adult-labelled categories will require this PIN. Leave blank to disable.").setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank() || value.length in 4..8) {
                    store.parentalPin = value.takeIf { it.isNotBlank() }
                    dialog.dismiss()
                } else {
                    input.error = "PIN must contain 4–8 digits"
                    input.requestFocus()
                }
            }
        }
        dialog.show()
        prepareCrownDialog(dialog)
    }

    private fun requiresPin(name: String): Boolean = store.parentalPin != null && !adultSessionUnlocked() && listOf("adult", "xxx", "18+").any { name.contains(it, true) }
    private fun adultSessionUnlocked(): Boolean = System.currentTimeMillis() < adultUnlockedUntil
    private fun verifyPin(after: () -> Unit) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val dialog = AlertDialog.Builder(this).setTitle("Enter parental PIN").setView(input).setPositiveButton("Unlock", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == store.parentalPin) {
                    adultUnlockedUntil = System.currentTimeMillis() + 15L * 60 * 1000
                    dialog.dismiss(); after()
                }
                else { input.error = "Incorrect PIN"; input.requestFocus() }
            }
        }
        dialog.show()
        prepareCrownDialog(dialog)
    }

    private fun confirmExit() { AlertDialog.Builder(this).setTitle("Exit Crown Media?").setPositiveButton("Exit") { _, _ -> finishAffinity() }.setNegativeButton("Stay", null).showCrown() }
    private fun openTrailer(value: String) {
        val url = if (value.startsWith("http")) value else "https://www.youtube.com/watch?v=$value"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { Toast.makeText(this, "No browser or YouTube app found", Toast.LENGTH_LONG).show() }
    }

    private fun sort(cards: List<CatalogCard>, target: Section = section): List<CatalogCard> {
        val ordered = when (store.sort) {
            "asc" -> cards.sortedBy { it.title.lowercase() }
            "desc" -> cards.sortedByDescending { it.title.lowercase() }
            else -> cards
        }
        val playlistId = store.selected()?.id
        return if (target == Section.LIVE && playlistId != null) {
            val statuses = ordered.associate { it.id to streamAvailability.status(playlistId, it.id) }
            ordered.sortedWith(
                compareBy<CatalogCard> { statuses.getValue(it.id).rank }
            )
        } else ordered
    }

    private fun scheduleLiveHealthRefresh(
        playlist: SavedPlaylist,
        visibleCards: List<CatalogCard>,
        categoryPool: List<CatalogCard> = visibleCards,
    ) {
        if (section != Section.LIVE || visibleCards.isEmpty()) return
        // Playback is the strongest health signal and always has priority. Accounts with a single
        // connection cannot safely run a sampler beside playback, so learn from player outcomes.
        val maximumConnections = playlist.maximumConnections ?: 1
        if (maximumConnections <= 1) return
        if (categoryPool.size > visibleCards.size) {
            if (healthJob?.isActive == true) return
            val now = System.currentTimeMillis()
            if (now - lastBroadHealthSampleAt < 6L * 60 * 60 * 1000) return
            lastBroadHealthSampleAt = now
        }
        healthJob?.cancel()
        val ranked = sort(visibleCards).filter { streamAvailability.shouldProbe(playlist.id, it.id) }
        val connectionLimit = (maximumConnections - 1).coerceIn(1, 2)
        val maximumProbes = if (connectionLimit == 1) 8 else 18
        val leading = ranked.take(if (connectionLimit == 1) 6 else 12)
        val leadingIds = leading.mapTo(mutableSetOf()) { it.id }
        val rotationBucket = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        val categorySamples = categoryPool.asSequence()
            .filter { it.id !in leadingIds && it.categoryId.isNotBlank() }
            .filter { streamAvailability.shouldProbe(playlist.id, it.id) }
            .distinctBy { it.categoryId }
            .sortedBy { "${it.categoryId}:$rotationBucket".hashCode() }
            .take(maximumProbes - leading.size)
            .toList()
        val candidates = leading + categorySamples
        if (candidates.isEmpty()) return
        healthJob = lifecycleScope.launch {
            val permits = Semaphore(connectionLimit)
            coroutineScope {
                candidates.map { card ->
                    async {
                        val available = permits.withPermit { probeLiveStream(playlist, card.id) }
                        if (available) streamAvailability.recordSuccess(playlist.id, card.id)
                        else streamAvailability.recordProbeFailure(playlist.id, card.id)
                    }
                }.awaitAll()
            }
            if (section == Section.LIVE && store.selected()?.id == playlist.id) renderCachedLive(playlist)
        }
    }

    private suspend fun probeLiveStream(playlist: SavedPlaylist, streamId: String): Boolean {
        val preferred = preferredLiveExtension(playlist.allowedFormats)
        if (api.probeLiveStream(playlist.credentials, streamId, preferred)) return true
        val alternate = when {
            preferred != "m3u8" && playlist.allowedFormats.any { it.equals("m3u8", true) } -> "m3u8"
            preferred != "ts" && playlist.allowedFormats.any { it.equals("ts", true) } -> "ts"
            else -> null
        }
        return alternate != null && api.probeLiveStream(playlist.credentials, streamId, alternate)
    }

    private suspend fun renderCachedLive(playlist: SavedPlaylist) {
        val state = sectionState(Section.LIVE)
        state.cards = withContext(Dispatchers.Default) {
            sort(state.cards, Section.LIVE)
        }
        if (section == Section.LIVE) catalogAdapter.submit(state.cards)
        refreshLiveCategoryRanking(playlist)
    }

    private fun refreshLiveCategoryRanking(playlist: SavedPlaylist) {
        liveRankingJob?.cancel()
        liveRankingJob = lifecycleScope.launch {
            delay(750)
            val providerCategories = cache.categories(playlist.id, Section.LIVE.cardKind)
            val favorites = store.favorites(playlist.id)
            val allCards = withContext(Dispatchers.Default) {
                cache.items(playlist.id, Section.LIVE.cardKind, null)
                    .map { it.toCard(Section.LIVE.cardKind, favorites) }
            }
            val ranked = withContext(Dispatchers.Default) {
                displayedCategories(
                    playlist.id,
                    providerCategories,
                    store.hiddenCategories(playlist.id),
                    allCards,
                    catalogComplete = store.catalogComplete(playlist.id, Section.LIVE.cardKind, null),
                    forSection = Section.LIVE,
                )
            }
            val state = sectionState(Section.LIVE)
            state.categories = ranked
            if (state.categories.none { it.id == state.categoryId }) state.categoryId = "all"
            if (section == Section.LIVE) {
                currentCategory = state.categoryId
                lastCategories = ranked
                categoriesAdapter.submit(ranked, currentCategory)
                scheduleLiveHealthRefresh(playlist, state.cards, allCards)
            }
        }
    }

    private fun displayedCategories(
        playlistId: String,
        providerCategories: List<XtreamCategory>,
        manuallyHidden: Set<String>,
        allLiveCards: List<CatalogCard>,
        catalogComplete: Boolean = false,
        forSection: Section = section,
    ): List<XtreamCategory> {
        val liveCardsByCategory = if (forSection == Section.LIVE) allLiveCards.groupBy { it.categoryId } else emptyMap()
        val visibleProviderCategories = providerCategories.filterNot { it.id in manuallyHidden }.filter { category ->
            // Only hide categories that are genuinely empty in a complete catalog. Health signals
            // rank categories; they never erase a category or block the user from retrying content.
            if (forSection != Section.LIVE || !catalogComplete) true
            else liveCardsByCategory[category.id].orEmpty().isNotEmpty()
        }.let { visible ->
            if (forSection != Section.LIVE) visible else {
                val quality = liveCardsByCategory.mapValues { (_, cards) -> categoryQuality(playlistId, cards) }
                visible.withIndex().sortedWith(
                compareBy<IndexedValue<XtreamCategory>> { indexed ->
                    quality[indexed.value.id]?.healthRank ?: 3
                }.thenBy { indexed ->
                    quality[indexed.value.id]?.failureWeight ?: Int.MAX_VALUE
                }.thenBy { it.index }
                ).map { it.value }
            }
        }
        return listOf(XtreamCategory("all", "All"), XtreamCategory("favorites", "Favorites")) + visibleProviderCategories
    }

    private fun categoryQuality(playlistId: String, cards: List<CatalogCard>): CategoryQuality {
        val statuses = cards.map { streamAvailability.status(playlistId, it.id) }
        val healthRank = when {
            StreamAvailability.Status.HEALTHY in statuses -> 0
            StreamAvailability.Status.UNKNOWN in statuses -> 1
            StreamAvailability.Status.TEMPORARILY_FAILED in statuses -> 2
            else -> 3
        }
        val weight = statuses.fold(0) { total, status -> total +
            when (status) {
                StreamAvailability.Status.REPEATEDLY_FAILED -> 2
                StreamAvailability.Status.TEMPORARILY_FAILED -> 1
                else -> 0
            }
        }
        val failureWeight = if (statuses.isEmpty()) Int.MAX_VALUE else weight * 100 / (statuses.size * 2)
        return CategoryQuality(healthRank, failureWeight)
    }

    private fun XtreamItem.toCard(kind: String, favorites: Set<String>): CatalogCard {
        val itemKind = kind
        val favorite = "$itemKind:$id" in favorites
        val badge = when { favorite -> "★"; catchUp -> "CATCH-UP"; !rating.isNullOrBlank() -> "★ $rating"; else -> "" }
        val healthHint = (if (catchUp) 4 else 0) + (if (!epgChannelId.isNullOrBlank()) 2 else 0) + (if (!imageUrl.isNullOrBlank()) 1 else 0)
        return CatalogCard(
            id, itemKind, name, imageUrl,
            listOfNotNull(year, genre, rating?.let { "Rating $it" }).joinToString(" • ").ifBlank { itemKind.uppercase() },
            badge, extension, categoryId, healthHint = healthHint,
            isAdult = isAdult,
        )
    }

    private fun showState(
        title: String,
        message: String,
        loading: Boolean,
        action: Boolean,
        actionLabel: String = "TRY AGAIN",
        keepCategories: Boolean = false,
    ) {
        if (deviceClass() == DeviceClass.TELEVISION && binding.contentGrid.hasFocus()) {
            pendingContentFocusKey = focusedCardKey()
        }
        binding.statePanel.isVisible = true
        binding.progress.isVisible = loading
        binding.stateTitle.text = if (loading) getString(R.string.loading) else title
        binding.stateMessage.text = message
        binding.stateMessage.isVisible = !loading && message.isNotBlank()
        binding.stateAction.isVisible = !loading && action
        binding.stateAction.text = actionLabel
        binding.contentGrid.isVisible = false
        binding.categoryList.isVisible = keepCategories && section != Section.HOME && section != Section.SEARCH
        binding.contentGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (action && deviceClass() == DeviceClass.TELEVISION) binding.stateAction.post { binding.stateAction.requestFocus() }
    }
    private fun hideState() {
        binding.statePanel.isVisible = false
        binding.contentGrid.isVisible = true
        binding.categoryList.isVisible = section != Section.HOME && section != Section.SEARCH
        binding.contentGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    private fun showFailure(error: Throwable) {
        if (error is CancellationException) return
        stateRetry = null
        showState("Couldn’t load content", friendly(error), false, true)
    }
    private fun showOperationFailure(error: Throwable, retry: () -> Unit) {
        stateRetry = null
        if (section in PAGED_SECTIONS) renderSectionState(sectionState(section), restoreScroll = true) else hideState()
        AlertDialog.Builder(this)
            .setTitle("Couldn’t load content")
            .setMessage(friendly(error))
            .setPositiveButton("Try again") { _, _ -> retry() }
            .setNegativeButton("Back", null)
            .showCrown()
    }

    private fun AlertDialog.Builder.showCrown(preferredButton: Int? = AlertDialog.BUTTON_POSITIVE): AlertDialog {
        val dialog = show()
        prepareCrownDialog(dialog, preferredButton)
        return dialog
    }

    private fun prepareCrownDialog(dialog: AlertDialog, preferredButton: Int? = AlertDialog.BUTTON_POSITIVE) {
        if (deviceClass() != DeviceClass.TELEVISION) return
        val buttons = listOfNotNull(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
        )
        buttons.forEach { button ->
            button.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.03f else 1f).scaleY(if (focused) 1.03f else 1f)
                    .translationZ(if (focused) 10f else 0f).setDuration(120).start()
            }
        }
        dialog.window?.decorView?.post {
            val target = preferredButton?.let(dialog::getButton) ?: dialog.listView
            target?.requestFocus()
        }
    }
    private fun friendly(error: Throwable): String = when (error) {
        is SecurityException -> "The server rejected the username or password."
        is java.net.UnknownHostException -> "The selected Crown service could not be reached. Check your internet connection and try again."
        is java.net.SocketTimeoutException -> "The server took too long to respond. Try again."
        is java.io.IOException -> when {
            error.message.orEmpty().startsWith("Server returned HTTP ") -> error.message.orEmpty().take(80)
            error.message == "Empty server response" -> "The provider returned an empty response. Try again."
            else -> "The provider connection failed. Check your connection and try again."
        }
        else -> "Unexpected provider response."
    }

    private fun deviceKey(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((packageName + store.installationId).toByteArray())
        return digest.take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }
    private fun qr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }

    private enum class Section(val apiKind: String, val cardKind: String, val displayName: String) {
        HOME("", "", "Home"), LIVE("live", "live", "Live TV"), MOVIES("vod", "movie", "Movies"), SERIES("series", "series", "Series"), SEARCH("", "", "Search")
    }

    private data class CategoryQuality(val healthRank: Int, val failureWeight: Int)

    private data class SeriesDetailState(
        val card: CatalogCard,
        val details: XtreamSeriesDetails,
        var season: Int,
    )

    private data class CatalogPage(
        val cards: List<CatalogCard>,
        val consumed: Int,
        val endReached: Boolean,
    ) {
        companion object { val EMPTY = CatalogPage(emptyList(), 0, true) }
    }

    private data class SectionState(
        var categoryId: String = "all",
        var categories: List<XtreamCategory> = emptyList(),
        var cards: List<CatalogCard> = emptyList(),
        var nextOffset: Int = 0,
        var endReached: Boolean = false,
        var loadingPage: Boolean = false,
        var scrollState: Parcelable? = null,
        var categoryScrollState: Parcelable? = null,
        var focusedCardKey: String? = null,
        var lastRefreshAt: Long = 0L,
    )

    companion object {
        private const val PAGE_SIZE = 60
        private const val NETWORK_BATCH_SIZE = 240
        private const val PREFETCH_DISTANCE = 12
        private const val REFRESH_TTL_MS = 15L * 60 * 1000
        private const val QR_CONNECT_UI_ENABLED = false
        private val PAGED_SECTIONS = setOf(Section.LIVE, Section.MOVIES, Section.SERIES)
        internal var storeFactory: (android.content.Context) -> AppStore = ::AppStore
    }
}
