package uk.crownmedia.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.crownmedia.core.database.CachedCatalogItem
import uk.crownmedia.core.database.CachedCategory
import uk.crownmedia.core.database.CatalogDao
import uk.crownmedia.core.database.CachedSearchToken
import uk.crownmedia.data.xtream.XtreamCategory
import uk.crownmedia.data.xtream.XtreamItem

class CatalogCache(private val dao: CatalogDao) {
    suspend fun categories(playlistId: String, kind: String): List<XtreamCategory> =
        withContext(Dispatchers.Default) { dao.categories(playlistId, kind).map { XtreamCategory(it.categoryId, it.name) } }

    suspend fun items(playlistId: String, kind: String, categoryId: String?): List<XtreamItem> =
        withContext(Dispatchers.Default) { dao.items(playlistId, kind, categoryId).map { it.toXtream() } }

    suspend fun itemPage(playlistId: String, kind: String, categoryId: String?, limit: Int, offset: Int, sort: String = "provider"): List<XtreamItem> =
        withContext(Dispatchers.Default) {
            when (sort) {
                "asc" -> dao.itemPageAscending(playlistId, kind, categoryId, limit, offset)
                "desc" -> dao.itemPageDescending(playlistId, kind, categoryId, limit, offset)
                else -> dao.itemPageProvider(playlistId, kind, categoryId, limit, offset)
            }.map { it.toXtream() }
        }

    suspend fun favoriteItemPage(playlistId: String, kind: String, contentIds: List<String>, limit: Int, offset: Int, sort: String = "provider"): List<XtreamItem> =
        if (contentIds.isEmpty()) emptyList()
        else withContext(Dispatchers.Default) {
            when (sort) {
                "asc" -> dao.favoriteItemPageAscending(playlistId, kind, contentIds, limit, offset)
                "desc" -> dao.favoriteItemPageDescending(playlistId, kind, contentIds, limit, offset)
                else -> dao.favoriteItemPageProvider(playlistId, kind, contentIds, limit, offset)
            }.map { it.toXtream() }
        }

    suspend fun itemCount(playlistId: String, kind: String, categoryId: String?): Int =
        dao.categoryItemCount(playlistId, kind, categoryId)

    /** Per-category item counts as a map, computed on the background dispatcher (TV-003/TV-004). */
    suspend fun categoryCounts(playlistId: String, kind: String): Map<String, Int> =
        withContext(Dispatchers.Default) {
            dao.categoryItemCounts(playlistId, kind)
                .filter { it.itemCount > 0 && it.categoryId.isNotBlank() }
                .associate { it.categoryId to it.itemCount }
        }

    /** Counts the same distinct, parental-accessible rows that catalog rendering can expose. */
    suspend fun accessibleCategoryCounts(playlistId: String, kind: String, includeAdult: Boolean): Map<String, Int> =
        withContext(Dispatchers.Default) {
            dao.accessibleCategoryItemCounts(playlistId, kind, includeAdult)
                .filter { it.itemCount > 0 && it.categoryId.isNotBlank() }
                .associate { it.categoryId to it.itemCount }
        }

    /** Aggregate per-category counts from SQL; avoids materializing the full catalog into memory. */
    suspend fun nonEmptyCategoryIds(playlistId: String, kind: String): Set<String> = withContext(Dispatchers.Default) {
        dao.categoryItemCounts(playlistId, kind)
            .asSequence()
            .filter { it.itemCount > 0 && it.categoryId.isNotBlank() }
            .mapTo(mutableSetOf()) { it.categoryId }
    }

    /**
     * Bounded per-category sample used for health/quality ranking. Never materializes the full
     * catalog: pulls only the first [perCategory] rows (provider order) for every category, which
     * keeps ranking work proportional to the number of categories, not the total item count.
     */
    suspend fun categorySamples(playlistId: String, kind: String, perCategory: Int = 8): List<XtreamItem> =
        withContext(Dispatchers.Default) {
            dao.categoryItemCounts(playlistId, kind)
                .filter { it.itemCount > 0 && it.categoryId.isNotBlank() }
                .flatMap { count ->
                    dao.itemPageProvider(
                        playlistId, kind, count.categoryId,
                        minOf(perCategory, count.itemCount), 0,
                    ).map { it.toXtream() }
                }
        }

    suspend fun search(playlistId: String, query: String, kind: String? = null, limit: Int = 240): List<Pair<String, XtreamItem>> = withContext(Dispatchers.Default) {
        val normalized = normalize(query)
        val tokens = tokens(normalized)
        if (tokens.isEmpty()) return@withContext emptyList()
        val anchor = tokens.maxBy(String::length)
        val candidates = if (kind == null) {
            dao.searchCandidates(playlistId, anchor, "$anchor\uFFFF", limit * 5)
        } else {
            dao.searchCandidatesForKind(playlistId, kind, anchor, "$anchor\uFFFF", limit * 5)
        }
        candidates
            .asSequence()
            .filter { item -> tokens.all { token -> item.normalizedTitle.split(' ').any { it.startsWith(token) } } }
            .take(limit)
            .map { it.kind to it.toXtream() }
            .toList()
    }

    suspend fun count(playlistId: String, kind: String): Int = dao.itemCount(playlistId, kind)

    suspend fun accessibleCount(playlistId: String, kind: String, includeAdult: Boolean): Int =
        withContext(Dispatchers.Default) { dao.accessibleItemCount(playlistId, kind, includeAdult) }

    suspend fun saveCategories(playlistId: String, kind: String, values: List<XtreamCategory>) {
        dao.replaceCategories(playlistId, kind, values.mapIndexed { index, value ->
            CachedCategory(playlistId, kind, value.id, value.name, index)
        })
    }

    suspend fun saveItems(playlistId: String, kind: String, categoryId: String?, values: List<XtreamItem>) {
        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.Default) { values.mapIndexed { index, value -> value.toCached(playlistId, kind, now, index) } }
        if (categoryId == null) dao.replaceAllItems(playlistId, kind, cached)
        else dao.replaceCategoryItems(playlistId, kind, categoryId, cached)
        val searchTokens = withContext(Dispatchers.Default) { cached.flatMap { item ->
            tokens(item.normalizedTitle).map { token -> CachedSearchToken(item.playlistId, item.kind, item.contentId, token) }
        } }
        if (cached.isNotEmpty()) dao.upsertItems(cached, searchTokens)
        dao.deleteOrphanSearchTokens(playlistId, kind)
    }

    suspend fun saveItemBatch(playlistId: String, kind: String, refreshMarker: Long, values: List<XtreamItem>, orderOffset: Int = 0) {
        if (values.isEmpty()) return
        val cached = withContext(Dispatchers.Default) { values.mapIndexed { index, value -> value.toCached(playlistId, kind, refreshMarker, orderOffset + index) } }
        val searchTokens = withContext(Dispatchers.Default) { cached.flatMap { item ->
            tokens(item.normalizedTitle).map { token -> CachedSearchToken(item.playlistId, item.kind, item.contentId, token) }
        } }
        dao.upsertItems(cached, searchTokens)
    }

    /** Removes stale rows only after a non-empty refresh completed successfully. */
    suspend fun finishItemRefresh(playlistId: String, kind: String, categoryId: String?, refreshMarker: Long, received: Int) {
        if (received == 0) return
        if (categoryId == null) dao.deleteStaleItems(playlistId, kind, refreshMarker)
        else dao.deleteStaleCategoryItems(playlistId, kind, categoryId, refreshMarker)
        dao.deleteOrphanSearchTokens(playlistId, kind)
    }

    suspend fun deletePlaylist(playlistId: String) = dao.deletePlaylist(playlistId)

    private fun XtreamItem.toCached(playlistId: String, kind: String, now: Long, fallbackOrder: Int) = CachedCatalogItem(
        playlistId = playlistId,
        kind = kind,
        contentId = id,
        categoryId = categoryId,
        title = name,
        normalizedTitle = normalize(name),
        imageUrl = imageUrl,
        rating = rating,
        addedEpochSeconds = addedEpochSeconds,
        extension = extension,
        epgChannelId = epgChannelId,
        catchUp = catchUp,
        catchUpDays = catchUpDays,
        providerOrder = providerOrder ?: fallbackOrder,
        isAdult = isAdult,
        updatedAtEpochMs = now,
    )

    private fun CachedCatalogItem.toXtream() = XtreamItem(
        id = contentId,
        categoryId = categoryId,
        name = title,
        imageUrl = imageUrl,
        rating = rating,
        addedEpochSeconds = addedEpochSeconds,
        extension = extension,
        epgChannelId = epgChannelId,
        catchUp = catchUp,
        catchUpDays = catchUpDays,
        providerOrder = providerOrder,
        isAdult = isAdult,
    )

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(NORMALIZE_PATTERN, " ")
        .trim()

    private fun tokens(value: String): List<String> = normalize(value).split(' ').filter { it.length >= 2 }.distinct()

    companion object {
        private val NORMALIZE_PATTERN = Regex("[^\\p{L}\\p{N}]+")
    }
}
