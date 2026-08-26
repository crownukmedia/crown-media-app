package uk.crownmedia.app

import uk.crownmedia.data.xtream.XtreamCategory
import uk.crownmedia.core.design.StreamAvailability
import java.util.Locale

internal const val CATEGORY_MENU_ID = "__category_menu__"

internal fun displayedCategoryList(
    providerCategories: List<XtreamCategory>,
    hiddenIds: Set<String> = emptySet(),
    includeMenu: Boolean = true,
    includeFavorites: Boolean = true,
): List<XtreamCategory> {
    val seenIds = mutableSetOf<String>()
    val seenNames = mutableSetOf<String>()
    val provider = providerCategories.asSequence()
        .filterNot { it.id in hiddenIds }
        .map { category ->
            val name = category.name.trim().ifBlank { "Uncategorized" }
            category.copy(name = name)
        }
        .filterNot(::isProviderAllCategory)
        .filter { category ->
            val idKey = category.id.trim().lowercase(Locale.ROOT)
            val nameKey = normalizedCategoryName(category.name)
            idKey.isNotBlank() && seenIds.add(idKey) && seenNames.add(nameKey)
        }
        .withIndex()
        .sortedWith(compareBy<IndexedValue<XtreamCategory>> { categoryRegionPriority(it.value.name) }.thenBy { it.index })
        .map { it.value }
        .toList()

    return buildList {
        if (includeMenu) add(XtreamCategory(CATEGORY_MENU_ID, "Categories"))
        add(XtreamCategory("all", "All"))
        if (includeFavorites) add(XtreamCategory("favorites", "Favorites"))
        addAll(provider)
    }
}

internal fun categoryRegionPriority(name: String): Int {
    val tokens = categoryTokens(name)
    val joined = tokens.joinToString(" ")
    return when {
        "united kingdom" in joined || "great britain" in joined ||
            tokens.any { it in UK_TOKENS } || tokens.windowed(2).any { it == listOf("u", "k") } -> 0
        tokens.any { it in IRELAND_TOKENS } -> 1
        else -> 2
    }
}

internal fun isProviderAllCategory(category: XtreamCategory): Boolean {
    if (category.id.trim().equals("all", ignoreCase = true)) return true
    val tokens = categoryTokens(category.name)
    return tokens.joinToString(" ") in PROVIDER_ALL_NAMES ||
        (tokens.firstOrNull() == "all" && tokens.drop(1).isNotEmpty() &&
            tokens.drop(1).all { it in GENERIC_ALL_SUFFIXES || it.all(Char::isDigit) })
}

internal fun prioritizeLiveCards(
    cards: List<CatalogCard>,
    status: (CatalogCard) -> StreamAvailability.Status,
): List<CatalogCard> = cards.sortedWith(compareBy { status(it).rank })

private fun normalizedCategoryName(value: String): String = categoryTokens(value).joinToString(" ")

private fun categoryTokens(value: String): List<String> = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)

private val UK_TOKENS = setOf("uk", "britain", "british")
private val IRELAND_TOKENS = setOf("ireland", "irish", "ie", "ir")
private val PROVIDER_ALL_NAMES = setOf(
    "all",
    "all category",
    "all categories",
    "all channel",
    "all channels",
    "all live",
    "all movie",
    "all movies",
    "all series",
    "everything",
)
private val GENERIC_ALL_SUFFIXES = setOf(
    "category", "categories", "channel", "channels", "content", "live", "movie", "movies", "series", "stream", "streams", "tv",
)
