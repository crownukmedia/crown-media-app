package uk.crownmedia.app

import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
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
import kotlin.math.roundToInt
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
    private lateinit var analytics: UsageAnalytics
    private lateinit var layoutSelection: LayoutSelection
    private lateinit var activeLayout: AppLayout
    private var detectedDeviceClass: DeviceClass = DeviceClass.PHONE
    private val api = XtreamClient()
    private lateinit var categoriesAdapter: CategoryAdapter
    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var contentLayoutManager: GridLayoutManager
    private var section = Section.HOME
    private var currentCategory = "all"
    private var loadJob: Job? = null
    private var loginJob: Job? = null
    private var searchJob: Job? = null
    private var scopedSearchJob: Job? = null
    private var countJob: Job? = null
    private var categoryCountJob: Job? = null
    private var categoryCountJobKey: String? = null
    private var healthJob: Job? = null
    private var liveRankingJob: Job? = null
    private var playlistRefreshJob: Job? = null
    private var detailJob: Job? = null
    private var searchWarmJob: Deferred<Result<Unit>>? = null
    private var searchWarmCompletedFor: String? = null
    private val catalogWarmJobs = mutableMapOf<String, Deferred<Result<Int>>>()
    private val contentCounts = EnumMap<Section, ContentCountState>(Section::class.java)
    private val sectionStates = EnumMap<Section, SectionState>(Section::class.java)
    private val refreshJobs = mutableMapOf<RefreshKey, Job>()
    private val catalogWork = CatalogWorkCoordinator()
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
    private var masterSearchQuery = ""
    private var updatingSearchBox = false
    private var contentRequestGeneration = 0L
    private var lastTrackedSearch: Int? = null
    private var categoryMenuDialog: AlertDialog? = null
    private var tvNavigationExpanded = false
    private var tvNavigationFocusEnabled = false
    private var tvNavigationAnimator: ValueAnimator? = null
    private val tvNavigationLabels = mutableMapOf<Int, CharSequence>()
    private val pendingTvFocusMoves = mutableSetOf<Int>()

    override fun attachBaseContext(newBase: Context) {
        detectedDeviceClass = newBase.deviceClass()
        val selectedLayout = LayoutSelection(newBase).resolve(detectedDeviceClass)
        val configuration = Configuration(newBase.resources.configuration).apply {
            val selectedUiMode = when (selectedLayout) {
                AppLayout.MOBILE -> Configuration.UI_MODE_TYPE_NORMAL
                AppLayout.TELEVISION -> Configuration.UI_MODE_TYPE_TELEVISION
            }
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or selectedUiMode
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        layoutSelection = LayoutSelection(this)
        activeLayout = layoutSelection.resolve(detectedDeviceClass)
        val launchLayout = layoutInflater.inflate(activeLayout.startupLayoutResource(), null, false)
        binding = ActivityMainBinding.bind(launchLayout)
        setContentView(binding.root)
        analytics = (application as CrownMediaApplication).usageAnalytics
        analytics.setDeviceClass(detectedDeviceClass)
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
                if (categoryMenuDialog?.isShowing == true) {
                    categoryMenuDialog?.dismiss()
                } else if (tvNavigationExpanded) {
                    setTvNavigationExpanded(false)
                    focusCurrentSectionContent()
                } else if (binding.loginPanel.root.isVisible && store.selected() != null) {
                    cancelLogin()
                    open(Section.HOME)
                } else if (nestedSeries != null) {
                    closeSeriesDetails()
                } else if (section in PAGED_SECTIONS && sectionState(section).searchQuery.isNotEmpty()) {
                    binding.searchBox.text.clear()
                    binding.searchBox.requestFocus()
                } else if (section == Section.SEARCH) {
                    hideKeyboard()
                    open(Section.HOME)
                } else if (section in PAGED_SECTIONS) {
                    open(Section.HOME)
                } else {
                    confirmExit()
                }
            }
        })
        val selectedPlaylist = store.selected()
        if (selectedPlaylist == null) showWelcome() else {
            refreshPlaybackCapabilitiesIfNeeded(selectedPlaylist)
            open(Section.HOME)
        }
        confirmDetectedTelevisionLayout(detectedDeviceClass)
    }

    private fun confirmDetectedTelevisionLayout(detectedDeviceClass: DeviceClass) {
        if (detectedDeviceClass != DeviceClass.TELEVISION || layoutSelection.hasUserChoice || isFinishing) return
        binding.root.post {
            if (isFinishing || isDestroyed || layoutSelection.hasUserChoice) return@post
            AlertDialog.Builder(this)
                .setTitle(R.string.tv_layout_detected)
                .setMessage(R.string.tv_layout_confirmation)
                .setPositiveButton(R.string.use_tv_layout) { _, _ ->
                    layoutSelection.select(AppLayout.TELEVISION)
                }
                .setNegativeButton(R.string.use_mobile_layout) { _, _ ->
                    layoutSelection.select(AppLayout.MOBILE)
                    recreate()
                }
                .setCancelable(false)
                .showCrown()
        }
    }

    private fun isTelevisionLayout(): Boolean = activeLayout == AppLayout.TELEVISION

    private fun configureLists() {
        val television = isTelevisionLayout()
        categoriesAdapter = CategoryAdapter(::selectCategory, ::hideCategory, ::handleCategoryDpad)
        binding.categoryList.layoutManager = LinearLayoutManager(
            this,
            if (television) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL,
            false,
        ).apply {
            initialPrefetchItemCount = if (television) 8 else 12
        }
        binding.categoryList.adapter = categoriesAdapter
        if (television) {
            binding.categoryBar.layoutParams = binding.categoryBar.layoutParams.apply {
                width = dp(responsiveTvCategoryNavigationWidthDp(resources.configuration.screenWidthDp))
            }
        }
        binding.categoryMenuButton.setOnClickListener {
            if (nestedSeries != null) closeSeriesDetails() else showCategoryMenu()
        }
        if (television) {
            binding.categoryMenuButton.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode !in TV_DPAD_KEYS) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> navigationView(section).requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> focusVisibleCatalogDestination(view)
                        // Back is the logical first row in the nested Series rail.
                        KeyEvent.KEYCODE_DPAD_DOWN -> requestRecyclerItemFocus(binding.categoryList, 0)
                        else -> view.requestFocus()
                    }
                    true
                }
            }
        }
        catalogAdapter = CatalogAdapter(::openCard, ::cardOptions, ::handleCatalogDpad)
        val initialColumns = when {
            television -> responsiveTvContentColumnCount(resources.configuration.screenWidthDp)
            resources.configuration.smallestScreenWidthDp >= 600 -> 4
            else -> 1
        }
        contentLayoutManager = GridLayoutManager(this, initialColumns)
        binding.contentGrid.layoutManager = contentLayoutManager
        binding.contentGrid.adapter = catalogAdapter
        binding.contentGrid.setHasFixedSize(true)
        binding.contentGrid.setItemViewCacheSize(8)
        // RecyclerView's default change/removal animations can keep outgoing Home holders drawn
        // after a destination list has committed on slower TVs. The TV shell uses immediate,
        // frame-synchronised route swaps instead; Mobile retains its existing animations.
        if (television) binding.contentGrid.itemAnimator = null
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
        binding.navHome.setOnClickListener { openFromNavigation(Section.HOME) }
        binding.navLive.setOnClickListener { openFromNavigation(Section.LIVE) }
        binding.navMovies.setOnClickListener { openFromNavigation(Section.MOVIES) }
        binding.navSeries.setOnClickListener { openFromNavigation(Section.SERIES) }
        binding.navSearch.setOnClickListener { openFromNavigation(Section.SEARCH) }
        binding.navAccount.setOnClickListener { showAccount() }
        binding.navSettings.setOnClickListener { showSettings() }
        binding.navExit.setOnClickListener { confirmExit() }
        binding.actionReload.setOnClickListener { if (section == Section.HOME) refreshAllCatalogs() else load(force = true) }
        binding.actionPlaylist.setOnClickListener { showPlaylists() }
        binding.actionMore.setOnClickListener(::showMobileMenu)
        binding.actionSearchClear.setOnClickListener {
            if (binding.searchBox.text.isNotEmpty()) binding.searchBox.text.clear()
            else if (section == Section.SEARCH) open(Section.HOME)
            else if (isTelevisionLayout()) restoreCategoryFocus(activeCategoryFocusId())
            else binding.categoryList.requestFocus()
        }
        binding.stateAction.setOnClickListener {
            val retry = stateRetry
            stateRetry = null
            if (retry != null) retry() else if (store.selected() == null) showManualPlaylist() else load(true)
        }
        if (isTelevisionLayout()) {
            binding.stateAction.setOnKeyListener { _, keyCode, event ->
                if (
                    event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                    section in PAGED_SECTIONS &&
                    binding.categoryList.isVisible &&
                    categoriesAdapter.itemCount > 0
                ) {
                    restoreCategoryFocus(activeCategoryFocusId())
                    true
                } else false
            }
            configureTvNavigationRail()
        }
    }

    private fun openFromNavigation(target: Section) {
        open(target)
        if (!isTelevisionLayout()) return
        setTvNavigationExpanded(false)
        binding.root.post(::focusCurrentSectionContent)
    }

    private fun configureTvNavigationRail() {
        val buttons = tvNavigationButtons()
        buttons.forEach { button ->
            tvNavigationLabels[button.id] = button.text
            button.contentDescription = button.text
            button.setOnFocusChangeListener { view, focused ->
                (view as? MaterialButton)?.strokeWidth =
                    ((if (focused) 3 else 1) * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                view.animate().scaleX(if (focused) 1.03f else 1f).scaleY(if (focused) 1.03f else 1f)
                    .translationZ(if (focused) 10f else 0f).setDuration(120).start()
                if (focused && tvNavigationFocusEnabled) {
                    setTvNavigationExpanded(true)
                } else {
                    binding.root.post {
                        if (buttons.none(View::hasFocus)) setTvNavigationExpanded(false)
                    }
                }
            }
            button.setOnKeyListener { _, keyCode, event ->
                if (
                    event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    section in PAGED_SECTIONS &&
                    binding.categoryBar.isVisible &&
                    categoriesAdapter.itemCount > 0
                ) {
                    setTvNavigationExpanded(false)
                    restoreCategoryFocus(activeCategoryFocusId())
                    true
                } else false
            }
        }
        applyTvNavigationLabels(expanded = false)
    }

    private fun setTvNavigationExpanded(expanded: Boolean) {
        if (!isTelevisionLayout() || tvNavigationExpanded == expanded) return
        val rail = binding.sideNav
        tvNavigationExpanded = expanded
        applyTvNavigationLabels(expanded)
        tvNavigationAnimator?.cancel()
        val collapsedWidth = dp(TV_NAV_COLLAPSED_WIDTH_DP)
        val responsiveExpandedDp = responsiveTvNavigationWidthDp(resources.configuration.screenWidthDp)
        val targetWidth = if (expanded) dp(responsiveExpandedDp) else collapsedWidth
        tvNavigationAnimator = ValueAnimator.ofInt(rail.width.takeIf { it > 0 } ?: rail.layoutParams.width, targetWidth).apply {
            duration = TV_NAV_ANIMATION_MS
            addUpdateListener { animator ->
                rail.layoutParams = rail.layoutParams.apply { width = animator.animatedValue as Int }
            }
            start()
        }
    }

    private fun applyTvNavigationLabels(expanded: Boolean) {
        tvNavigationButtons().forEach { button ->
            button.text = if (expanded) tvNavigationLabels[button.id] ?: "" else ""
            button.gravity = if (expanded) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            button.iconPadding = if (expanded) dp(TV_NAV_ICON_PADDING_DP) else 0
            val horizontalPadding = if (expanded) dp(TV_NAV_BUTTON_PADDING_DP) else 0
            button.setPadding(horizontalPadding, button.paddingTop, horizontalPadding, button.paddingBottom)
            button.contentDescription = tvNavigationLabels[button.id] ?: ""
        }
    }

    private fun focusCurrentSectionContent() {
        when {
            binding.contentGrid.isVisible && catalogAdapter.itemCount > 0 -> restoreCardFocus(sectionState(section).focusedCardKey)
            section in PAGED_SECTIONS && binding.categoryList.isVisible && categoriesAdapter.itemCount > 0 -> restoreCategoryFocus(activeCategoryFocusId())
            section in PAGED_SECTIONS && binding.categoryMenuButton.isVisible -> binding.categoryMenuButton.requestFocus()
            section == Section.SEARCH -> binding.searchBox.requestFocus()
            binding.searchBox.isVisible -> binding.searchBox.requestFocus()
            binding.stateAction.isVisible -> binding.stateAction.requestFocus()
            else -> binding.contentGrid.requestFocus()
        }
    }

    private fun handleCatalogDpad(view: View, position: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (!isTelevisionLayout() || event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode !in TV_DPAD_KEYS) return false
        // Held remotes can deliver repeats faster than RecyclerView can lay out the next row.
        // Consume repeats while one frame-bounded focus transition is pending instead of stacking
        // scroll/focus callbacks against stale adapter positions.
        if (binding.contentGrid.id in pendingTvFocusMoves) return true
        val move = tvContentFocusMove(
            position,
            catalogAdapter.itemCount,
            contentLayoutManager.spanCount,
            keyCode,
            section in PAGED_SECTIONS && binding.categoryBar.isVisible,
            binding.searchRow.isVisible,
        )
        when (move.region) {
            TvFocusRegion.ITEM -> requestRecyclerItemFocus(binding.contentGrid, move.position)
            TvFocusRegion.SIDEBAR -> navigationView(section).requestFocus()
            TvFocusRegion.CATEGORIES -> restoreCategoryFocus(activeCategoryFocusId())
            TvFocusRegion.SEARCH -> binding.searchBox.requestFocus()
            else -> view.requestFocus()
        }
        return true
    }

    private fun handleCategoryDpad(view: View, position: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (!isTelevisionLayout() || event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode !in TV_DPAD_KEYS) return false
        if (binding.categoryList.id in pendingTvFocusMoves) return true
        val move = tvCategoryFocusMove(
            position,
            categoriesAdapter.itemCount,
            keyCode,
            binding.categoryMenuButton.isVisible,
            catalogAdapter.itemCount > 0,
        )
        when (move.region) {
            TvFocusRegion.ITEM -> requestRecyclerItemFocus(binding.categoryList, move.position)
            TvFocusRegion.CATEGORY_MENU -> binding.categoryMenuButton.requestFocus()
            TvFocusRegion.SIDEBAR -> navigationView(section).requestFocus()
            TvFocusRegion.CONTENT -> focusVisibleCatalogDestination(view)
            else -> view.requestFocus()
        }
        return true
    }

    private fun focusVisibleCatalogDestination(fallback: View) {
        when {
            binding.contentGrid.isVisible && catalogAdapter.itemCount > 0 -> {
                val key = if (nestedSeries != null) null else sectionState(section).focusedCardKey
                restoreCardFocus(key)
            }
            binding.stateAction.isVisible -> binding.stateAction.requestFocus()
            else -> fallback.requestFocus()
        }
    }

    private fun requestRecyclerItemFocus(recyclerView: RecyclerView, position: Int) {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0 || position !in 0 until itemCount) return
        // Adjacent TV rows are normally already attached. Focus them synchronously so a second
        // deliberate D-pad press is never mistaken for a stale repeat while waiting for another
        // animation frame. Off-screen targets retain the bounded async scroll/layout path below.
        if (recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() == true) return
        if (!pendingTvFocusMoves.add(recyclerView.id)) return
        val target = position

        fun focusAfterLayout(remainingAttempts: Int) {
            recyclerView.postOnAnimation {
                val currentCount = recyclerView.adapter?.itemCount ?: 0
                if (!recyclerView.isAttachedToWindow || currentCount == 0) {
                    pendingTvFocusMoves.remove(recyclerView.id)
                    return@postOnAnimation
                }
                val safeTarget = target.coerceIn(0, currentCount - 1)
                recyclerView.scrollToPosition(safeTarget)
                val focused = recyclerView.findViewHolderForAdapterPosition(safeTarget)
                    ?.itemView
                    ?.requestFocus() == true
                if (focused || remainingAttempts == 0) {
                    pendingTvFocusMoves.remove(recyclerView.id)
                } else {
                    focusAfterLayout(remainingAttempts - 1)
                }
            }
        }

        focusAfterLayout(2)
    }

    private fun restoreCategoryFocus(categoryId: String) {
        // Resolve against the adapter's active list. Nested Series replaces the provider
        // categories with seasons, so consulting SectionState here would incorrectly map every
        // Content -> Seasons transition back to the parent category list.
        val position = categoriesAdapter.positionOf(categoryId).coerceAtLeast(0)
        requestRecyclerItemFocus(binding.categoryList, position)
    }

    private fun activeCategoryFocusId(): String =
        nestedSeries?.let { "season:${it.season}" } ?: currentCategory

    private fun tvNavigationButtons(): List<MaterialButton> = listOf(
        binding.navHome,
        binding.navLive,
        binding.navMovies,
        binding.navSeries,
        binding.navSearch,
        binding.navAccount,
        binding.navSettings,
        binding.navExit,
    ).mapNotNull { it as? MaterialButton }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                runActiveSearch(binding.searchBox.text.toString())
                hideKeyboard(clearFocus = false)
                true
            } else false
        }
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updatingSearchBox || (section != Section.SEARCH && section !in PAGED_SECTIONS)) return
                val query = s?.toString().orEmpty()
                if (section == Section.SEARCH) masterSearchQuery = query else sectionState(section).searchQuery = query
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(350); runActiveSearch(query) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun runActiveSearch(query: String) {
        val normalized = query.trim()
        if (normalized.length >= MIN_SEARCH_LENGTH) {
            val signature = 31 * section.hashCode() + normalized.hashCode()
            if (signature != lastTrackedSearch) {
                lastTrackedSearch = signature
                analytics.trackSearch(section.name.lowercase())
            }
        }
        if (section == Section.SEARCH) search(query)
        else if (section in PAGED_SECTIONS) searchCategory(section, query)
    }

    private fun bindSearchBox(target: Section) {
        val query = if (target == Section.SEARCH) masterSearchQuery else sectionState(target).searchQuery
        updatingSearchBox = true
        binding.searchBox.setText(query)
        binding.searchBox.setSelection(query.length)
        binding.searchBox.hint = when (target) {
            Section.LIVE -> getString(R.string.search_live)
            Section.MOVIES -> getString(R.string.search_movies)
            Section.SERIES -> getString(R.string.search_series)
            else -> getString(R.string.search_everything)
        }
        updatingSearchBox = false
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
        val changingTopLevelSection = section != value
        captureSectionState()
        detailJob?.cancel()
        detailJob = null
        nestedSeries = null
        stateRetry = null
        loadJob?.cancel()
        scopedSearchJob?.cancel()
        searchJob?.cancel()
        if (changingTopLevelSection) {
            contentRequestGeneration++
            searchShouldFocusResults = false
            masterSearchQuery = ""
            if (section in PAGED_SECTIONS) sectionState(section).searchQuery = ""
            if (value in PAGED_SECTIONS) {
                cancelSectionRefreshes(value)
                sectionState(value).resetForTopLevelEntry()
            }
        }
        if (value != Section.LIVE) {
            healthJob?.cancel()
            liveRankingJob?.cancel()
        }
        val navigationStarted = SystemClock.elapsedRealtime()
        showAuthenticatedShell()
        section = value
        // Supersede any pending AsyncListDiffer commit from a Home count update before the
        // destination begins loading. Otherwise the old Home tiles can flash over the new route.
        if (changingTopLevelSection && value != Section.HOME) catalogAdapter.submit(emptyList())
        binding.pageProgress.isVisible = false
        val state = sectionState(value)
        currentCategory = state.categoryId
        val searchVisible = value == Section.SEARCH || value in PAGED_SECTIONS
        binding.searchRow.isVisible = searchVisible
        binding.actionSearchClear.isVisible = searchVisible
        if (searchVisible) bindSearchBox(value)
        val television = isTelevisionLayout()
        binding.actionMore.isVisible = !television && value != Section.SEARCH
        binding.actionReload.isVisible = !television
        binding.actionPlaylist.isVisible = !television
        setCategoryNavigationVisible(value != Section.HOME && value != Section.SEARCH)
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
        if (changingTopLevelSection && value in PAGED_SECTIONS) {
            // Keep the previous route hidden until AsyncListDiffer commits the destination rows.
            // Showing the destination loader here prevents Home/last-section cards flashing while
            // the background diff is still replacing them.
            showState("Loading", "", true, false, keepCategories = true)
        }
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
            if (state.searchQuery.trim().length >= MIN_SEARCH_LENGTH) searchCategory(value, state.searchQuery)
            else renderSectionState(state, restoreScroll = true)
            startCatalogRefresh(store.selected() ?: return, value, force = false, hadContent = true)
        } else {
            load()
        }
        if (BuildConfig.DEBUG) Log.d("CrownPerformance", "tab_${value.name.lowercase()}_render_ms=${SystemClock.elapsedRealtime() - navigationStarted}")
        analytics.trackScreen(value.name.lowercase())
    }

    private fun updateSelectedNavigation(value: Section) {
        binding.navHome.isSelected = value == Section.HOME
        binding.navLive.isSelected = value == Section.LIVE
        binding.navMovies.isSelected = value == Section.MOVIES
        binding.navSeries.isSelected = value == Section.SERIES
        binding.navSearch.isSelected = value == Section.SEARCH
        binding.sideNav.contentDescription = getString(R.string.selected_destination, value.displayName)
        updateCountNavigation()
    }

    private fun configureTvPresentation(value: Section) {
        if (!isTelevisionLayout()) return
        contentLayoutManager.spanCount = if (value == Section.HOME) 3 else {
            responsiveTvContentColumnCount(resources.configuration.screenWidthDp)
        }
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
        binding.categoryMenuButton.nextFocusLeftId = activeNav.id
        binding.categoryMenuButton.nextFocusRightId = binding.contentGrid.id
        binding.categoryMenuButton.nextFocusUpId = binding.searchBox.id
        binding.categoryMenuButton.nextFocusDownId = binding.categoryList.id
        binding.categoryList.nextFocusLeftId = activeNav.id
        binding.categoryList.nextFocusRightId = binding.contentGrid.id
        binding.contentGrid.nextFocusLeftId = if (value in PAGED_SECTIONS) binding.categoryList.id else activeNav.id
        binding.contentGrid.nextFocusUpId = if (value in PAGED_SECTIONS) binding.searchBox.id else binding.topBar.id
        binding.searchBox.nextFocusLeftId = activeNav.id
        binding.searchBox.nextFocusDownId = if (value in PAGED_SECTIONS) binding.categoryList.id else binding.contentGrid.id
        binding.actionSearchClear.nextFocusLeftId = binding.searchBox.id
        binding.actionSearchClear.nextFocusDownId = binding.contentGrid.id
        binding.stateAction.nextFocusLeftId = if (value in PAGED_SECTIONS) binding.categoryList.id else activeNav.id
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
        val requestGeneration = contentRequestGeneration
        val requestedCategory = state.categoryId
        if (state.cards.isEmpty()) showState("Loading", "", true, false, keepCategories = target in PAGED_SECTIONS)
        loadJob = lifecycleScope.launch {
            val cacheStarted = SystemClock.elapsedRealtime()
            val cachedResult = runCatching {
                val categories = cache.categories(playlist.id, target.cardKind)
                val page = cachedPage(playlist, target, requestedCategory, 0)
                categories to page
            }
            if (section != target || requestGeneration != contentRequestGeneration || state.categoryId != requestedCategory) return@launch
            val (providerCategories, page) = cachedResult.getOrNull() ?: (emptyList<XtreamCategory>() to CatalogPage.EMPTY)
            state.categories = baseCategories(providerCategories, playlist.id)
            // TV-009 safety net: a category preserved from a previous visit may no longer
            // exist after a catalog refresh — fall back to "All" instead of a stuck empty state.
            if (state.categories.none { it.id == state.categoryId }) {
                state.categoryId = "all"
                state.cards = emptyList()
                state.nextOffset = 0
                state.endReached = false
                state.scrollState = null
                state.categoryScrollState = null
            }
            state.cards = page.cards
            state.nextOffset = page.consumed
            state.endReached = page.endReached
            if (state.cards.isNotEmpty()) {
                if (state.searchQuery.trim().length >= MIN_SEARCH_LENGTH) searchCategory(target, state.searchQuery)
                else renderSectionState(state, restoreScroll = false)
                if (BuildConfig.DEBUG) Log.d("CrownPerformance", "${target.name.lowercase()}_cache_first_page_ms=${SystemClock.elapsedRealtime() - cacheStarted}")
                binding.screenSubtitle.text = getString(R.string.updating_in_background, playlist.name)
                if (target == Section.LIVE) {
                    scheduleLiveHealthRefresh(playlist, state.cards)
                    refreshLiveCategoryRanking(playlist)
                }
            } else if (state.searchQuery.trim().length >= MIN_SEARCH_LENGTH) {
                searchCategory(target, state.searchQuery)
            }
            startCatalogRefresh(
                playlist,
                target,
                force = force || providerCategories.isEmpty(),
                hadContent = state.cards.isNotEmpty(),
            )
        }
    }

    private fun selectCategory(category: XtreamCategory) {
        if (category.id.startsWith("season:")) {
            selectSeriesSeason(category.id.substringAfter(':').toIntOrNull() ?: return)
            return
        }
        if (requiresPin(category.name)) { verifyPin { selectCategory(category) }; return }
        if (category.id == currentCategory) {
            scrollCategoryIntoView(category.id)
            return
        }
        val restoreTvCategoryFocus = isTelevisionLayout() && binding.categoryList.hasFocus()
        contentRequestGeneration++
        val state = sectionState(section)
        state.searchQuery = ""
        bindSearchBox(section)
        state.apply {
            scrollState = null
            cards = emptyList()
            nextOffset = 0
            endReached = false
            categoryId = category.id
            generation++
        }
        currentCategory = category.id
        categoriesAdapter.submit(state.categories, currentCategory) {
            scrollCategoryIntoView(currentCategory)
            if (restoreTvCategoryFocus) restoreCategoryFocus(activeCategoryFocusId())
        }
        if (restoreTvCategoryFocus) setTvNavigationExpanded(false)
        cancelSectionRefreshes(section)
        analytics.trackCategorySelected(section.name.lowercase())
        load()
    }

    private fun scrollCategoryIntoView(categoryId: String) {
        sectionState(section).categories.indexOfFirst { it.id == categoryId }
            .takeIf { it >= 0 }
            ?.let(binding.categoryList::smoothScrollToPosition)
    }

    private fun showCategoryMenu() {
        if (section !in PAGED_SECTIONS || nestedSeries != null || isFinishing) return
        val choices = sectionState(section).categories
        if (choices.isEmpty()) {
            Toast.makeText(this, "No categories available", Toast.LENGTH_SHORT).show()
            return
        }
        categoryMenuDialog?.dismiss()
        val selected = choices.indexOfFirst { it.id == currentCategory }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.all_categories)
            .setSingleChoiceItems(choices.map { it.name }.toTypedArray(), selected) { activeDialog, which ->
                val chosen = choices.getOrNull(which) ?: return@setSingleChoiceItems
                activeDialog.dismiss()
                selectCategory(chosen)
            }
            .setNegativeButton("Close", null)
            .showCrown(preferredButton = null)
        categoryMenuDialog = dialog
        if (selected >= 0) dialog.listView.setSelection(selected)
        dialog.setOnDismissListener { if (categoryMenuDialog === dialog) categoryMenuDialog = null }
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
        scopedSearchJob?.cancel()
        countJob?.cancel()
        categoryCountJob?.cancel()
        categoryCountJobKey = null
        catalogWarmJobs.values.forEach { it.cancel() }
        catalogWarmJobs.clear()
        sectionStates.clear()
        contentCounts.clear()
        searchWarmJob = null
        searchWarmCompletedFor = null
        masterSearchQuery = ""
        activePlaylistId = playlistId
        updateCountNavigation()
    }

    private fun sectionState(value: Section): SectionState =
        sectionStates.getOrPut(value) { SectionState() }

    private fun captureSectionState() {
        if (nestedSeries != null) return
        if (section !in PAGED_SECTIONS && section != Section.HOME) return
        sectionState(section).apply {
            categoryId = currentCategory
            scrollState = binding.contentGrid.layoutManager?.onSaveInstanceState()
            categoryScrollState = binding.categoryList.layoutManager?.onSaveInstanceState()
            focusedCardKey = focusedCardKey()
        }
    }

    private fun renderSectionState(state: SectionState, restoreScroll: Boolean) {
        if (section in PAGED_SECTIONS && sectionStates[section] !== state) return
        val target = section
        categoriesAdapter.submit(state.categories, state.categoryId) {
            if (restoreScroll) state.categoryScrollState?.let { binding.categoryList.layoutManager?.onRestoreInstanceState(it) }
        }
        store.selected()?.let { loadCategoryCounts(it, target) }
        catalogAdapter.submit(state.cards) {
            if (section != target || sectionStates[target] !== state) return@submit
            val revealDestination = reveal@{
                if (section != target || sectionStates[target] !== state) return@reveal
                hideState()
                if (restoreScroll) state.scrollState?.let { binding.contentGrid.layoutManager?.onRestoreInstanceState(it) }
                val focusKey = pendingContentFocusKey ?: if (binding.contentGrid.hasFocus()) focusedCardKey() else null
                pendingContentFocusKey = null
                if (focusKey != null && isTelevisionLayout()) restoreCardFocus(focusKey)
            }
            // AsyncListDiffer commits before RecyclerView completes the layout/draw frame. Keep
            // the TV grid invisible for that frame so outgoing Home/last-route holders can never
            // flash between the loader and destination content.
            if (isTelevisionLayout() && binding.contentGrid.visibility != View.VISIBLE) {
                binding.contentGrid.postOnAnimation { revealDestination() }
            } else revealDestination()
        }
        if (section in PAGED_SECTIONS && isTelevisionLayout()) {
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
        return displayedCategoryList(providerCategories, store.hiddenCategories(playlistId))
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
        if (state.searchQuery.isNotBlank()) return
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
                val combined = state.cards + page.cards.filter { known.add("${it.kind}:${it.id}") }
                state.cards = if (target == Section.LIVE) sort(combined, Section.LIVE) else combined
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
        // Interactive category work must never queue behind background count/index warm-up.
        cancelCatalogWarmup(playlist.id, target.cardKind)
        val state = sectionState(target)
        if (playlistRefreshJob?.isActive == true) {
            if (hadContent) return
            playlistRefreshJob?.cancel()
        }
        val persistedRefreshAt = store.catalogRefreshAt(playlist.id, target.cardKind, state.categoryId.takeUnless { it == "all" || it == "favorites" })
        state.lastRefreshAt = maxOf(state.lastRefreshAt, persistedRefreshAt)
        if (!force && hadContent && System.currentTimeMillis() - state.lastRefreshAt < REFRESH_TTL_MS) return
        // TV-002: key refreshes by (target, categoryId, generation) so a category switch or a
        // top-level re-entry supersedes any stale in-flight refresh instead of being silently
        // swallowed by an unrelated active job (which left the UI stuck in a loading state).
        val refreshKey = RefreshKey(target, state.categoryId, state.generation)
        val activeForSection = refreshJobs.filterKeys { it.target == target }
        activeForSection.keys
            .firstOrNull { it.categoryId == refreshKey.categoryId && it.generation == refreshKey.generation }
            ?.let { if (refreshJobs[it]?.isActive == true) return }
        if (activeForSection.isNotEmpty()) {
            activeForSection.values.forEach(Job::cancel)
            refreshJobs.keys.removeAll { it.target == target }
        }
        if (!hadContent) {
            healthJob?.cancel()
        }
        val refreshCategory = state.categoryId
        val job = lifecycleScope.launch {
            try {
                catalogWork.interactive {
                    refreshCatalog(playlist, target, refreshCategory, hadContent)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Throwable) {
                // Only surface errors for a refresh that still owns the current category/generation.
                if (section == target && state.categoryId == refreshCategory && state.generation == refreshKey.generation) {
                    if (state.cards.isEmpty()) showFailure(error)
                    else binding.screenSubtitle.text = getString(R.string.offline_catalog, playlist.name)
                }
            }
        }
        refreshJobs[refreshKey] = job
        job.invokeOnCompletion { if (refreshJobs[refreshKey] === job) refreshJobs.remove(refreshKey) }
    }

    /** Cancel every in-flight refresh scoped to [target]; used on category switch / top-level re-entry. */
    private fun cancelSectionRefreshes(target: Section) {
        refreshJobs.filterKeys { it.target == target }.values.forEach(Job::cancel)
        refreshJobs.keys.removeAll { it.target == target }
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
        if (section == target && state.categoryId == refreshCategory) {
            categoriesAdapter.submit(state.categories, state.categoryId)
            store.selected()?.let { loadCategoryCounts(it, target) }
        }
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
                        if (state.searchQuery.isBlank()) renderSectionState(state, restoreScroll = false)
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
        if (section == target) loadCategoryCounts(playlist, target, force = true)
        if (actualCategory == null || store.catalogComplete(playlist.id, target.cardKind, null)) {
            updateContentCount(
                target,
                ContentCountState.Ready(
                    cache.accessibleCount(playlist.id, target.cardKind, includeAdultContent()),
                ),
            )
        }
        if (BuildConfig.DEBUG) Log.d("CrownPerformance", "${target.name.lowercase()}_refresh_total_ms=${SystemClock.elapsedRealtime() - refreshStarted};items=$received")
        if (state.categoryId == refreshCategory) {
            val visibleCount = state.cards.size.coerceAtLeast(PAGE_SIZE)
            val refreshed = cachedPage(playlist, target, refreshCategory, 0, visibleCount)
            state.cards = refreshed.cards
            state.nextOffset = refreshed.consumed
            state.endReached = refreshed.endReached
        }
        if (section == target && state.categoryId == refreshCategory) {
            currentCategory = state.categoryId
            if (state.searchQuery.trim().length >= MIN_SEARCH_LENGTH) {
                searchCategory(target, state.searchQuery)
                binding.screenSubtitle.text = playlist.name
            } else if (state.cards.isEmpty()) {
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
                try {
                    catalogWork.interactive {
                        refreshCatalog(playlist, target, "all", hadContent = true)
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
        PAGED_SECTIONS.forEach { contentCounts.putIfAbsent(it, ContentCountState.Loading) }
        renderHome(p, focusContent = isTelevisionLayout())
        loadContentCounts(p)
    }

    private fun renderHome(p: SavedPlaylist, focusContent: Boolean = false) {
        val expiry = p.expiresAt?.let { DateFormat.getDateInstance().format(Date(it * 1000)) } ?: "No expiry reported"
        val cards = listOf(
            CatalogCard("live", "home", "Live TV", null, "${countState(Section.LIVE).homeDescription("channels")} • EPG & catch-up", "WATCH", localArtwork = R.drawable.home_live_icon),
            CatalogCard("movies", "home", "Movies", null, "${countState(Section.MOVIES).homeDescription("movies")} • Details & resume", "EXPLORE", localArtwork = R.drawable.home_movies_icon),
            CatalogCard("series", "home", "Series", null, "${countState(Section.SERIES).homeDescription("series")} • Seasons & episodes", "BINGE", localArtwork = R.drawable.home_series_icon),
            CatalogCard("account", "home", "${p.name} account", null, "${p.status} • $expiry", "ACTIVE", localArtwork = R.drawable.home_account_icon),
            CatalogCard("reload", "home", "Reload content", null, "Refresh provider catalogs", "SYNC", localArtwork = R.drawable.home_reload_icon),
            CatalogCard("playlist", "home", "Change playlist", null, "${store.playlists().size} playlist(s)", "SWITCH", localArtwork = R.drawable.home_playlist_icon),
        )
        val state = sectionState(Section.HOME).apply { this.cards = cards; categories = emptyList() }
        catalogAdapter.submit(cards) {
            state.scrollState?.let { binding.contentGrid.layoutManager?.onRestoreInstanceState(it) }
            if (focusContent && section == Section.HOME && isTelevisionLayout()) {
                restoreCardFocus(state.focusedCardKey)
                binding.contentGrid.post { tvNavigationFocusEnabled = true }
            }
        }
    }

    private fun countState(target: Section): ContentCountState =
        contentCounts[target] ?: ContentCountState.Loading

    private fun updateCountNavigation() {
        if (!::binding.isInitialized) return
        val television = isTelevisionLayout()
        updateNavigationText(binding.navLive, countState(Section.LIVE).navigationLabel(getString(R.string.nav_live), television = television))
        updateNavigationText(binding.navMovies, countState(Section.MOVIES).navigationLabel(getString(R.string.nav_movies), television = television))
        updateNavigationText(binding.navSeries, countState(Section.SERIES).navigationLabel(getString(R.string.nav_series), television = television))
    }

    private fun updateNavigationText(view: TextView, label: CharSequence) {
        if (!isTelevisionLayout()) {
            view.text = label
            return
        }
        tvNavigationLabels[view.id] = label
        view.contentDescription = label
        view.text = if (tvNavigationExpanded) label else ""
    }

    private fun updateContentCount(target: Section, value: ContentCountState) {
        updateContentCounts(mapOf(target to value))
    }

    /** Publishes a count snapshot with one navigation update and at most one Home list diff. */
    private fun updateContentCounts(values: Map<Section, ContentCountState>) {
        if (values.none { (target, value) -> contentCounts[target] != value }) return
        values.forEach { (target, value) -> contentCounts[target] = value }
        updateCountNavigation()
        if (section == Section.HOME) store.selected()?.let(::renderHome)
    }

    private fun loadContentCounts(playlist: SavedPlaylist) {
        countJob?.cancel()
        countJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val includeAdult = includeAdultContent()
                val cachedSnapshot = coroutineScope {
                    PAGED_SECTIONS.map { target ->
                        async {
                            val complete = store.catalogComplete(playlist.id, target.cardKind, null)
                            val count = if (complete) {
                                runCatching {
                                    cache.accessibleCount(playlist.id, target.cardKind, includeAdult)
                                }.getOrDefault(0)
                            } else 0
                            target to if (complete) ContentCountState.Ready(count) else ContentCountState.Loading
                        }
                    }.awaitAll().toMap()
                }
                if (activePlaylistId != playlist.id) return@repeatOnLifecycle
                // Complete Room catalogs render all three totals immediately in one frame.
                updateContentCounts(cachedSnapshot)

                val missing = PAGED_SECTIONS.filter { cachedSnapshot[it] == ContentCountState.Loading }
                if (missing.isEmpty()) return@repeatOnLifecycle
                val warmResults = coroutineScope {
                    missing.map { target ->
                        async { target to awaitCatalogWarmResult(playlist, target.cardKind) }
                    }.awaitAll().toMap()
                }
                if (activePlaylistId != playlist.id) return@repeatOnLifecycle

                val finalSnapshot = cachedSnapshot.toMutableMap()
                missing.forEach { target ->
                    val completed = warmResults[target]?.isSuccess == true ||
                        store.catalogComplete(playlist.id, target.cardKind, null)
                    finalSnapshot[target] = if (completed) {
                        val count = runCatching {
                            cache.accessibleCount(playlist.id, target.cardKind, includeAdultContent())
                        }.getOrDefault(0)
                        ContentCountState.Ready(count)
                    } else ContentCountState.Unavailable
                }
                // Do not make Home visibly count Live, then Movies, then Series. Publish the
                // completed provider snapshot atomically once all parallel requests settle.
                updateContentCounts(finalSnapshot)
            }
        }
    }

    private suspend fun awaitCatalogWarmResult(playlist: SavedPlaylist, kind: String): Result<Int> = try {
        warmCatalogKind(playlist, kind).await()
    } catch (error: CancellationException) {
        if (!currentCoroutineContext().isActive) throw error
        // An interactive visit intentionally supersedes the same background warm-up. Other
        // catalog counts must continue rather than losing the whole three-kind snapshot.
        Result.failure(error)
    }

    /** TV-004: load per-category counts on a background dispatcher, independent of catalog indexing. */
    private fun loadCategoryCounts(playlist: SavedPlaylist, target: Section, force: Boolean = false) {
        if (target !in PAGED_SECTIONS) return
        val includeAdult = includeAdultContent()
        val key = "${playlist.id}:${target.cardKind}:$includeAdult"
        if (!force && categoryCountJobKey == key && categoryCountJob?.isActive == true) return
        categoryCountJob?.cancel()
        val playlistId = playlist.id
        categoryCountJobKey = key
        val job = lifecycleScope.launch {
            val counts = withContext(Dispatchers.Default) {
                runCatching {
                    cache.accessibleCategoryCounts(playlistId, target.cardKind, includeAdult)
                }.getOrDefault(emptyMap())
            }
            if (section == target && activePlaylistId == playlistId) {
                categoriesAdapter.updateCounts(counts)
            }
        }
        categoryCountJob = job
        job.invokeOnCompletion {
            if (categoryCountJob === job) {
                categoryCountJob = null
                categoryCountJobKey = null
            }
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
                "live" -> openFromNavigation(Section.LIVE)
                "movies" -> openFromNavigation(Section.MOVIES)
                "series" -> openFromNavigation(Section.SERIES)
                "account" -> showAccount(); "reload" -> refreshAllCatalogs(); "playlist" -> showPlaylists()
            }; return
        }
        analytics.trackContentOpened(card.kind)
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
        analytics.trackPlaybackRequested(card.kind, store.player, live)
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
                if (isTelevisionLayout()) builder.setView(contentDetailsView(card, message))
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
                showSeriesOverview(card, details)
            }.onFailure { error ->
                if (error !is CancellationException && section == Section.SERIES) showOperationFailure(error) { showSeries(card) }
            }
        }
    }

    private fun showSeriesOverview(card: CatalogCard, details: XtreamSeriesDetails) {
        hideState()
        if (details.episodes.values.all { it.isEmpty() }) {
            val builder = AlertDialog.Builder(this).setTitle(details.name)
            if (isTelevisionLayout()) builder.setView(contentDetailsView(card, details.plot ?: "No episodes supplied."))
            else builder.setMessage(details.plot ?: "No episodes supplied.")
            builder.setPositiveButton("Close", null).showCrown()
            return
        }
        val openEpisodes = {
            nestedSeries = SeriesDetailState(card, details, details.episodes.keys.minOrNull() ?: 1)
            renderSeriesDetails()
        }
        if (isTelevisionLayout()) {
            AlertDialog.Builder(this)
                .setTitle(details.name)
                .setView(contentDetailsView(card, listOfNotNull(details.genre, details.plot).joinToString("\n\n")))
                .setPositiveButton("Browse episodes") { _, _ -> openEpisodes() }
                .setNegativeButton("Back", null)
                .showCrown()
        } else openEpisodes()
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
        setCategoryNavigationVisible(true)
        // Commit the season rail before exposing/focusing its episode content. On slower TVs an
        // immediate Left press could previously arrive while AsyncListDiffer still contained the
        // parent Series categories, leaving the remote with no valid season target.
        categoriesAdapter.submit(seasons, "season:${nested.season}", committed = categoryCommit@{
            if (nestedSeries !== nested || section != Section.SERIES) return@categoryCommit
            if (episodes.isEmpty()) {
                catalogAdapter.submit(emptyList())
                stateRetry = { closeSeriesDetails() }
                showState(
                    "No episodes",
                    "This season does not contain any episodes. Choose another season above or return to Series.",
                    false,
                    true,
                    getString(R.string.back_to_series),
                    keepCategories = true,
                )
            } else {
                catalogAdapter.submit(episodes, committed = episodeCommit@{
                    if (nestedSeries !== nested || section != Section.SERIES) return@episodeCommit
                    hideState()
                    if (isTelevisionLayout()) restoreCardFocus(null)
                })
            }
        })
    }

    private fun selectSeriesSeason(season: Int) {
        val nested = nestedSeries ?: return
        nested.season = season
        renderSeriesDetails()
    }

    private fun closeSeriesDetails() {
        val previous = nestedSeries ?: return
        nestedSeries = null
        detailJob?.cancel()
        detailJob = null
        binding.screenTitle.text = "Series"
        binding.screenSubtitle.text = store.selected()?.name.orEmpty()
        setCategoryNavigationVisible(true)
        pendingContentFocusKey = "${previous.card.kind}:${previous.card.id}"
        renderSectionState(sectionState(Section.SERIES), restoreScroll = true)
        if (isTelevisionLayout()) {
            binding.root.post {
                if (section == Section.SERIES && nestedSeries == null && !isFinishing) {
                    showSeriesOverview(previous.card, previous.details)
                }
            }
        }
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
                            val start = formatCatchUpStart(startTimestamp, playlist.serverTimezone)
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
        if (query.trim().length < MIN_SEARCH_LENGTH) {
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

    private fun searchCategory(target: Section, query: String) {
        if (target !in PAGED_SECTIONS || section != target) return
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            scopedSearchJob?.cancel()
            searchShouldFocusResults = false
            renderSectionState(sectionState(target), restoreScroll = true)
            return
        }
        if (normalizedQuery.length < MIN_SEARCH_LENGTH) {
            scopedSearchJob?.cancel()
            searchShouldFocusResults = false
            showState(
                "Type at least $MIN_SEARCH_LENGTH characters",
                "Search is limited to ${target.displayName.lowercase()}.",
                false,
                false,
                keepCategories = true,
            )
            binding.searchBox.requestFocus()
            return
        }
        val playlist = store.selected() ?: return
        scopedSearchJob?.cancel()
        scopedSearchJob = lifecycleScope.launch {
            suspend fun cachedResults(): List<CatalogCard> = cache.search(
                playlist.id,
                normalizedQuery,
                kind = target.cardKind,
                limit = SEARCH_RESULT_LIMIT,
            ).map { (_, item) -> item.toCard(target.cardKind, store.favorites(playlist.id)) }
                .filterNot { it.isAdult && store.parentalPin != null && !adultSessionUnlocked() }

            var cards = runCatching { cachedResults() }.getOrDefault(emptyList())
            if (!isCurrentCategorySearch(target, normalizedQuery)) return@launch
            if (cards.isNotEmpty()) {
                hideState()
                submitScopedSearchResults(target, cards)
                binding.screenSubtitle.text = getString(R.string.saved_results, playlist.name)
            } else {
                showState("Searching ${target.displayName}", "Looking through your saved ${target.displayName.lowercase()} catalog…", true, false, keepCategories = true)
            }

            if (!store.catalogComplete(playlist.id, target.cardKind, null)) {
                val warmResult = warmCatalogKind(playlist, target.cardKind).await()
                if (!isCurrentCategorySearch(target, normalizedQuery)) return@launch
                if (warmResult.isFailure && cards.isEmpty()) {
                    showState("Search unavailable", "Couldn’t finish loading ${target.displayName.lowercase()}. Try again when the provider is reachable.", false, true, keepCategories = true)
                    return@launch
                }
                cards = runCatching { cachedResults() }.getOrDefault(cards)
            }

            if (!isCurrentCategorySearch(target, normalizedQuery)) return@launch
            hideState()
            submitScopedSearchResults(target, cards)
            binding.screenSubtitle.text = playlist.name
            if (cards.isEmpty()) {
                searchShouldFocusResults = false
                stateRetry = { binding.searchBox.text.clear(); binding.searchBox.requestFocus() }
                showState(
                    "No ${target.displayName} results",
                    "No titles match “$normalizedQuery” in ${target.displayName.lowercase()}.",
                    false,
                    true,
                    "CLEAR SEARCH",
                    keepCategories = true,
                )
            }
        }
    }

    private fun isCurrentCategorySearch(target: Section, query: String): Boolean =
        section == target && sectionState(target).searchQuery.trim() == query

    private fun submitScopedSearchResults(target: Section, cards: List<CatalogCard>) {
        catalogAdapter.submit(sort(cards, target)) {
            if (searchShouldFocusResults && cards.isNotEmpty() && section == target) {
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
            warmCatalogKind(playlist, kind).await().getOrThrow()
            onKindComplete()
        }
        Unit
    }

    private fun warmCatalogKind(playlist: SavedPlaylist, kind: String): Deferred<Result<Int>> {
        val key = "${playlist.id}:$kind"
        catalogWarmJobs[key]?.takeIf { it.isActive }?.let { return it }
        val job = lifecycleScope.async {
            val result = catalogWork.background {
                runCatching {
                    val target = PAGED_SECTIONS.firstOrNull { it.cardKind == kind }
                        ?: error("Unsupported catalog kind: $kind")
                    if (cache.categories(playlist.id, kind).isEmpty()) {
                        runCatching { api.categories(playlist.credentials, target.apiKind) }
                            .getOrNull()
                            ?.let { cache.saveCategories(playlist.id, kind, it) }
                    }
                    if (store.catalogComplete(playlist.id, kind, null)) return@runCatching cache.count(playlist.id, kind)
                    val refreshMarker = System.currentTimeMillis()
                    var received = 0
                    api.catalogBatches(playlist.credentials, kind, batchSize = NETWORK_BATCH_SIZE).collect { batch ->
                        cache.saveItemBatch(playlist.id, kind, refreshMarker, batch, received)
                        received += batch.size
                    }
                    cache.finishItemRefresh(playlist.id, kind, null, refreshMarker, received)
                    if (received > 0) store.markCatalogRefreshed(playlist.id, kind, null)
                    cache.count(playlist.id, kind)
                }
            }
            result
        }
        catalogWarmJobs[key] = job
        job.invokeOnCompletion { if (catalogWarmJobs[key] === job) catalogWarmJobs.remove(key) }
        return job
    }

    private fun cancelCatalogWarmup(playlistId: String, kind: String) {
        catalogWarmJobs.remove("$playlistId:$kind")?.cancel()
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
        analytics.trackScreen("login")
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
        setCategoryNavigationVisible(false)
        binding.contentGrid.isVisible = false
        binding.statePanel.isVisible = false
        root.isVisible = true
        loginCancel.isVisible = canGoBack
        loginCancel.isEnabled = canGoBack
        if (isTelevisionLayout()) serviceDropdown.requestFocus() else playlistName.requestFocus()
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
                    analytics.trackLogin("inactive", service)
                    setLoginLoading(false)
                    formError.text = getString(R.string.inactive_account, account.status.name.lowercase())
                    formError.isVisible = true
                } else {
                    analytics.trackLogin("success", service)
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
                        serverTimezone = account.serverTimezone,
                        persist = saveLogin.isChecked,
                    )
                    setLoginLoading(false)
                    open(Section.HOME)
                }
            }.onFailure { error ->
                if (error is CancellationException || generation != loginGeneration) return@onFailure
                analytics.trackLogin("failure", service)
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
        val maximumQrDp = if (isTelevisionLayout()) 360 else 300
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
        analytics.trackScreen("account")
        val expiry = p.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) } ?: "Not supplied"
        val dialog = AlertDialog.Builder(this).setTitle(p.name).setMessage(
            "STATUS\n${p.status}\n\nEXPIRY\n$expiry\n\nCONNECTIONS\n${p.activeConnections ?: "Not supplied"} active of ${p.maximumConnections ?: "Not supplied"} maximum\n\nSERVER\n${Uri.parse(p.credentials.serverUrl).host ?: "Configured"}\n\nUSERNAME\nSaved securely"
        ).setPositiveButton("Refresh account") { _, _ -> refreshAccount(p) }
            .setNeutralButton("Remove playlist") { _, _ -> confirmRemove(p) }
            .setNegativeButton("Close", null).showCrown()
        if (isTelevisionLayout()) {
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
                    serverTimezone = a.serverTimezone,
                )
                Toast.makeText(this@MainActivity, "Account refreshed", Toast.LENGTH_SHORT).show(); showAccount()
            }.onFailure { error ->
                if (error !is CancellationException) Toast.makeText(this@MainActivity, friendly(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPlaybackCapabilitiesIfNeeded(selectedPlaylist: SavedPlaylist? = store.selected()) {
        val playlist = selectedPlaylist?.takeIf {
            it.allowedFormats.isEmpty() || it.serverTimezone.isNullOrBlank()
        } ?: return
        lifecycleScope.launch {
            runCatching { api.authenticate(playlist.credentials) }.onSuccess { account ->
                store.save(
                    playlist.name, playlist.credentials, account.expiresAtEpochSeconds, account.status.name,
                    account.activeConnections, account.maximumConnections, id = playlist.id,
                    allowedFormats = account.allowedFormats,
                    serverTimezone = account.serverTimezone,
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
        analytics.trackScreen("settings")
        val playerLabel = when (store.player) { "vlc" -> "VLC"; "mx" -> "MX Player"; "chooser" -> "System chooser"; else -> "Internal Crown Player" }
        val bufferLabel = when (store.buffer) { "low" -> "Low latency"; "resilient" -> "Resilient"; else -> "Normal" }
        val sortLabel = when (store.sort) { "asc" -> "Name A–Z"; "desc" -> "Name Z–A"; else -> "Provider order" }
        val labels = arrayOf(
            "Playback player  •  $playerLabel",
            "Buffer profile  •  $bufferLabel",
            "Content sorting  •  $sortLabel",
            "Parental controls  •  ${if (store.parentalPin == null) "Off" else "On"}",
            "Restore hidden categories",
            "Share usage and playback error  •  ${when { !analytics.isConfigured -> "Unavailable"; analytics.isEnabled -> "On"; else -> "Off" }}",
            getString(R.string.app_layout_setting, getString(layoutSelection.preference.labelResource)),
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
                5 -> showUsageAnalyticsChoice()
                6 -> showLayoutChoice()
            }
        }.setNegativeButton("Close", null).showCrown(preferredButton = null)
    }

    private fun showLayoutChoice() {
        val preferences = AppLayoutPreference.entries
        val labels = preferences.map { getString(it.labelResource) }.toTypedArray()
        val selected = preferences.indexOf(layoutSelection.preference).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.app_layout)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val preference = preferences.getOrNull(which) ?: return@setSingleChoiceItems
                layoutSelection.select(preference)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.back, null)
            .showCrown(preferredButton = null)
    }

    private fun showUsageAnalyticsChoice() {
        if (!analytics.isConfigured) {
            Toast.makeText(this, "Usage analytics is not configured in this build", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("On", "Off")
        AlertDialog.Builder(this)
            .setTitle("Share usage and playback error")
            .setSingleChoiceItems(options, if (analytics.isEnabled) 0 else 1) { dialog, which ->
                analytics.updateConsent(which == 0)
                if (which == 0) analytics.setDeviceClass(deviceClass())
                dialog.dismiss()
                showSettings()
            }
            .setNegativeButton("Back", null)
            .showCrown(preferredButton = null)
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
    private fun includeAdultContent(): Boolean = store.parentalPin == null || adultSessionUnlocked()
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

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit Crown Media?")
            .setMessage("Are you sure you want to exit the app?")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .showCrown()
    }
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
            prioritizeLiveCards(ordered) { streamAvailability.status(playlistId, it.id) }
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(750)
                val providerCategories = cache.categories(playlist.id, Section.LIVE.cardKind)
                val favorites = store.favorites(playlist.id)
                // TV-001/TV-006: never materialize the full Live catalog into memory. Use the SQL
                // aggregate for empty-category filtering and a bounded per-category sample for
                // health/quality ranking — ranking stays proportional to visible candidates.
                val visibleState = sectionState(Section.LIVE)
                val visibleCards = visibleState.cards
                val nonEmpty = withContext(Dispatchers.Default) {
                    cache.nonEmptyCategoryIds(playlist.id, Section.LIVE.cardKind)
                }
                val sampleCards = withContext(Dispatchers.Default) {
                    cache.categorySamples(playlist.id, Section.LIVE.cardKind, perCategory = 8)
                        .map { it.toCard(Section.LIVE.cardKind, favorites) }
                }
                val ranked = withContext(Dispatchers.Default) {
                    displayedCategoriesRanked(
                        playlist,
                        providerCategories,
                        store.hiddenCategories(playlist.id),
                        nonEmpty,
                        sampleCards,
                        catalogComplete = store.catalogComplete(playlist.id, Section.LIVE.cardKind, null),
                    )
                }
                val state = sectionState(Section.LIVE)
                state.categories = ranked
                if (state.categories.none { it.id == state.categoryId }) state.categoryId = "all"
                if (section == Section.LIVE) {
                    currentCategory = state.categoryId
                    categoriesAdapter.submit(ranked, currentCategory)
                    loadCategoryCounts(playlist, Section.LIVE)
                    scheduleLiveHealthRefresh(playlist, visibleCards, sampleCards + visibleCards)
                }
            }
        }
    }

    private fun displayedCategoriesRanked(
        playlist: SavedPlaylist,
        providerCategories: List<XtreamCategory>,
        manuallyHidden: Set<String>,
        nonEmptyCategories: Set<String>,
        sampleCards: List<CatalogCard>,
        catalogComplete: Boolean = false,
    ): List<XtreamCategory> {
        val playlistId = playlist.id
        val sampleCardsByCategory = sampleCards.groupBy { it.categoryId }
        val visibleProviderCategories = providerCategories.filter { category ->
            // Only hide categories that are genuinely empty in a complete catalog. The SQL
            // aggregate (nonEmptyCategories) drives this without materializing the full catalog.
            if (!catalogComplete) true
            else category.id in nonEmptyCategories
        }.let { visible ->
            val quality = sampleCardsByCategory.mapValues { (_, cards) -> categoryQuality(playlistId, cards) }
            visible.withIndex().sortedWith(
                compareBy<IndexedValue<XtreamCategory>> { indexed ->
                    quality[indexed.value.id]?.healthRank ?: 3
                }.thenBy { indexed ->
                    quality[indexed.value.id]?.failureWeight ?: Int.MAX_VALUE
                }.thenBy { it.index }
            ).map { it.value }
        }
        return displayedCategoryList(visibleProviderCategories, manuallyHidden)
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
        if (isTelevisionLayout() && binding.contentGrid.hasFocus()) {
            pendingContentFocusKey = focusedCardKey()
            // Explicitly transfer focus before hiding the focused grid. Otherwise Android's
            // spatial fallback can choose the navigation rail and expand it during details,
            // playback preparation, or a route transition.
            when {
                section in PAGED_SECTIONS && binding.categoryList.isVisible && categoriesAdapter.itemCount > 0 -> restoreCategoryFocus(activeCategoryFocusId())
                section in PAGED_SECTIONS && binding.categoryMenuButton.isVisible -> binding.categoryMenuButton.requestFocus()
                binding.searchBox.isVisible -> binding.searchBox.requestFocus()
            }
        }
        binding.statePanel.isVisible = true
        binding.progress.isVisible = loading
        binding.stateTitle.text = if (loading) getString(R.string.loading) else title
        binding.stateMessage.text = message
        binding.stateMessage.isVisible = !loading && message.isNotBlank()
        binding.stateAction.isVisible = !loading && action
        binding.stateAction.text = actionLabel
        binding.contentGrid.visibility = if (isTelevisionLayout() && section in PAGED_SECTIONS) {
            // INVISIBLE keeps RecyclerView measured so destination children are laid out behind
            // the loader before the next-frame reveal.
            View.INVISIBLE
        } else View.GONE
        // In paged destinations the header/category bar is a fixed sibling of the scrolling grid.
        // Keep it mounted through loading, empty, error, and detail-fetch states so it remains
        // sticky and D-pad reachable. Home and global Search intentionally have no category bar.
        setCategoryNavigationVisible((keepCategories || section in PAGED_SECTIONS) && section != Section.HOME && section != Section.SEARCH)
        binding.contentGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (action && isTelevisionLayout()) binding.stateAction.post { binding.stateAction.requestFocus() }
    }
    private fun hideState() {
        binding.statePanel.isVisible = false
        binding.contentGrid.isVisible = true
        setCategoryNavigationVisible(section != Section.HOME && section != Section.SEARCH)
        binding.contentGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun setCategoryNavigationVisible(visible: Boolean) {
        binding.categoryBar.isVisible = visible
        val nested = nestedSeries != null
        binding.categoryMenuButton.isVisible = visible && section in PAGED_SECTIONS && (!isTelevisionLayout() || nested)
        binding.root.findViewById<TextView>(R.id.category_panel_title)?.text =
            getString(if (nested) R.string.seasons else R.string.categories)
        binding.categoryMenuButton.setImageResource(if (nested) R.drawable.ic_arrow_back else R.drawable.ic_categories_menu)
        binding.categoryMenuButton.contentDescription = getString(
            when {
                !nested -> R.string.open_all_categories
                isTelevisionLayout() -> R.string.back_to_series_details
                else -> R.string.back_to_series_listing
            },
        )
    }
    private fun showFailure(error: Throwable) {
        if (error is CancellationException) return
        stateRetry = null
        showState(
            "Couldn’t load content",
            friendly(error),
            false,
            true,
            keepCategories = section in PAGED_SECTIONS,
        )
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
        if (!isTelevisionLayout()) return
        val buttons = listOfNotNull(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
        )
        val textColors = ContextCompat.getColorStateList(this, R.color.tv_button_text)
        val backgroundColors = ContextCompat.getColorStateList(this, R.color.tv_button_background)
        val strokeColors = ContextCompat.getColorStateList(this, R.color.tv_button_stroke)
        buttons.forEach { button ->
            button.setTextColor(textColors)
            ViewCompat.setBackgroundTintList(button, backgroundColors)
            (button as? MaterialButton)?.strokeColor = strokeColors
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

    /** Ownership key for a catalog refresh: scoped to (section, categoryId, generation) so a
     *  category switch or top-level re-entry invalidates any in-flight stale refresh. */
    private data class RefreshKey(val target: Section, val categoryId: String, val generation: Long)

    private data class SectionState(
        var categoryId: String = "all",
        var generation: Long = 0L,
        var categories: List<XtreamCategory> = emptyList(),
        var cards: List<CatalogCard> = emptyList(),
        var nextOffset: Int = 0,
        var endReached: Boolean = false,
        var loadingPage: Boolean = false,
        var scrollState: Parcelable? = null,
        var categoryScrollState: Parcelable? = null,
        var focusedCardKey: String? = null,
        var lastRefreshAt: Long = 0L,
        var searchQuery: String = "",
    ) {
        fun resetForTopLevelEntry() {
            // TV-009: preserve category selection, cards, and scroll position when returning
            // to a top-level section; only transient search/loading state resets.
            searchQuery = ""
            loadingPage = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val NETWORK_BATCH_SIZE = 240
        private const val PREFETCH_DISTANCE = 12
        private const val REFRESH_TTL_MS = 15L * 60 * 1000
        private const val MIN_SEARCH_LENGTH = 2
        private const val SEARCH_RESULT_LIMIT = 500
        private const val QR_CONNECT_UI_ENABLED = false
        private const val TV_NAV_COLLAPSED_WIDTH_DP = 88
        private const val TV_NAV_MIN_EXPANDED_WIDTH_DP = 220
        private const val TV_NAV_MAX_EXPANDED_WIDTH_DP = 260
        private const val TV_NAV_ANIMATION_MS = 180L
        private const val TV_NAV_ICON_PADDING_DP = 16
        private const val TV_NAV_BUTTON_PADDING_DP = 16
        private const val TV_CATEGORY_MIN_WIDTH_DP = 180
        private const val TV_CATEGORY_MAX_WIDTH_DP = 240
        private const val TV_CONTENT_MIN_CARD_WIDTH_DP = 145
        private val TV_DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        private val PAGED_SECTIONS = setOf(Section.LIVE, Section.MOVIES, Section.SERIES)
        internal var storeFactory: (android.content.Context) -> AppStore = ::AppStore

        internal fun responsiveTvNavigationWidthDp(screenWidthDp: Int): Int =
            (screenWidthDp * 0.32f).toInt()
                .coerceIn(TV_NAV_MIN_EXPANDED_WIDTH_DP, TV_NAV_MAX_EXPANDED_WIDTH_DP)

        internal fun responsiveTvCategoryNavigationWidthDp(screenWidthDp: Int): Int =
            (screenWidthDp * 0.22f).toInt()
                .coerceIn(TV_CATEGORY_MIN_WIDTH_DP, TV_CATEGORY_MAX_WIDTH_DP)

        internal fun responsiveTvContentColumnCount(screenWidthDp: Int): Int {
            val available = screenWidthDp - TV_NAV_COLLAPSED_WIDTH_DP -
                responsiveTvCategoryNavigationWidthDp(screenWidthDp) - 60
            return (available.toFloat() / TV_CONTENT_MIN_CARD_WIDTH_DP).roundToInt().coerceIn(2, 5)
        }
    }
}
