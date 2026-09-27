package uk.crownmedia.app

import uk.crownmedia.data.xtream.XtreamCategory
import java.util.Locale

internal fun displayedCategoryList(
    providerCategories: List<XtreamCategory>,
    hiddenIds: Set<String> = emptySet(),
    includeFavorites: Boolean = true,
): List<XtreamCategory> {
    val seenIds = mutableSetOf<String>()
    val provider = providerCategories.asSequence()
        .filterNot { it.id in hiddenIds }
        .map { category ->
            val name = category.name.trim().ifBlank { "Uncategorized" }
            category.copy(name = name)
        }
        .filterNot(::isProviderAllCategory)
        .filter { category ->
            val idKey = category.id.trim().lowercase(Locale.ROOT)
            // A provider may deliberately expose multiple bouquets with the same display name.
            // Category IDs are the stable identity; collapsing by name hides valid content.
            idKey.isNotBlank() && seenIds.add(idKey)
        }
        .toList()

    return buildList {
        add(XtreamCategory("all", "All"))
        if (includeFavorites) add(XtreamCategory("favorites", "Favorites"))
        addAll(provider)
    }
}

internal fun isProviderAllCategory(category: XtreamCategory): Boolean {
    if (category.id.trim().equals("all", ignoreCase = true)) return true
    val tokens = categoryTokens(category.name)
    return tokens.joinToString(" ") in PROVIDER_ALL_NAMES ||
        (tokens.firstOrNull() == "all" && tokens.drop(1).isNotEmpty() &&
            tokens.drop(1).all { it in GENERIC_ALL_SUFFIXES || it.all(Char::isDigit) })
}

internal fun availableCategoriesInProviderOrder(
    providerCategories: List<XtreamCategory>,
    nonEmptyCategoryIds: Set<String>,
    catalogComplete: Boolean,
): List<XtreamCategory> = if (catalogComplete) {
    providerCategories.filter { it.id in nonEmptyCategoryIds }
} else providerCategories

internal fun orderedCatalogCards(cards: List<CatalogCard>, order: String): List<CatalogCard> = when (order) {
    "asc" -> cards.sortedBy { it.title.lowercase() }
    "desc" -> cards.sortedByDescending { it.title.lowercase() }
    else -> cards
}

private fun categoryTokens(value: String): List<String> = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)

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
