package uk.crownmedia.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.crownmedia.core.model.ProviderCredentials
import java.util.UUID

data class SavedPlaylist(
    val id: String,
    val name: String,
    val credentials: ProviderCredentials,
    val expiresAt: Long?,
    val status: String,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val allowedFormats: List<String> = emptyList(),
    val serverTimezone: String? = null,
)

data class SavedLoginDetails(
    val playlistName: String,
    val service: CrownService,
    val username: String,
    val password: String,
)

class AppStore internal constructor(private val prefs: CrownSecureStore) {
    constructor(context: Context) : this(CrownSecureStore.create(context))
    private var transientPlaylist: SavedPlaylist? = null
    private var persistedPlaylistCache: List<SavedPlaylist>? = null
    private var selectedIdCacheInitialized = false
    private var selectedIdCache: String? = null
    private val contentCountCache = mutableMapOf<String, Int?>()
    private val categoryCountCache = mutableMapOf<String, Map<String, Int>?>()

    var selectedId: String?
        get() {
            if (!selectedIdCacheInitialized) {
                selectedIdCache = prefs.getString("selected", null)
                selectedIdCacheInitialized = true
            }
            return selectedIdCache
        }
        set(value) {
            if (transientPlaylist?.id != value) transientPlaylist = null
            selectedIdCache = value
            selectedIdCacheInitialized = true
            prefs.putString("selected", value)
        }

    var player: String
        get() = prefs.getString("player", "internal") ?: "internal"
        set(value) { prefs.putString("player", value) }

    var buffer: String
        get() = prefs.getString("buffer", "normal") ?: "normal"
        set(value) { prefs.putString("buffer", value) }

    var sort: String
        get() = prefs.getString("sort", "provider") ?: "provider"
        set(value) { prefs.putString("sort", value) }

    var parentalPin: String?
        get() = prefs.getString("parental_pin", null)
        set(value) { prefs.putString("parental_pin", value) }

    val installationId: String
        get() = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.putString("installation_id", it)
        }

    fun savedLoginDetails(service: CrownService): SavedLoginDetails? {
        val stored = prefs.getString(savedLoginKey(service), null)
            ?: (if (service == CrownService.PREMIUM) prefs.getString(LEGACY_SAVED_LOGIN_KEY, null) else null)
            ?: return null
        return try {
            val value = JSONObject(stored)
            SavedLoginDetails(
                playlistName = value.optString("playlistName"),
                service = CrownService.fromStoredValue(value.optString("service")),
                username = value.getString("username"),
                password = value.getString("password"),
            ).takeIf { it.service == service }
        } catch (_: Exception) { null }
    }

    fun saveLoginDetails(service: CrownService, value: SavedLoginDetails?) {
        require(value == null || value.service == service)
        prefs.putString(savedLoginKey(service), value?.let {
            JSONObject().apply {
                put("playlistName", it.playlistName)
                put("service", it.service.name)
                put("username", it.username)
                put("password", it.password)
            }.toString()
        })
        if (service == CrownService.PREMIUM) prefs.putString(LEGACY_SAVED_LOGIN_KEY, null)
    }

    private fun savedLoginKey(service: CrownService) = "saved_login_${service.name}"

    private fun persistedPlaylists(): List<SavedPlaylist> = persistedPlaylistCache ?: try {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toPlaylist() }
            .also { persistedPlaylistCache = it }
    } catch (_: Exception) {
        emptyList<SavedPlaylist>().also { persistedPlaylistCache = it }
    }

    fun playlists(): List<SavedPlaylist> = persistedPlaylists() + listOfNotNull(transientPlaylist)

    fun selected(): SavedPlaylist? = transientPlaylist ?: persistedPlaylists().firstOrNull { it.id == selectedId }

    fun save(
        name: String,
        credentials: ProviderCredentials,
        expiresAt: Long?,
        status: String,
        active: Int?,
        maximum: Int?,
        id: String = UUID.randomUUID().toString(),
        allowedFormats: List<String> = emptyList(),
        serverTimezone: String? = null,
        persist: Boolean = transientPlaylist?.id != id,
    ): SavedPlaylist {
        val value = SavedPlaylist(id, name, credentials, expiresAt, status, active, maximum, allowedFormats, serverTimezone)
        if (persist) {
            transientPlaylist = null
            write(persistedPlaylists().filterNot { it.id == id } + value)
            selectedId = id
        } else {
            selectedId = null
            transientPlaylist = value
        }
        return value
    }

    fun remove(id: String) {
        val removedTransient = transientPlaylist?.id == id
        if (removedTransient) transientPlaylist = null
        val remaining = persistedPlaylists().filterNot { it.id == id }
        write(remaining)
        clearCatalogCountSnapshots(id)
        if (selectedId == id || (removedTransient && selectedId == null)) selectedId = remaining.firstOrNull()?.id
    }

    fun catalogRefreshAt(playlistId: String, kind: String, categoryId: String?): Long =
        prefs.getString(refreshKey("at", playlistId, kind, categoryId), "0")?.toLongOrNull() ?: 0L

    fun catalogComplete(playlistId: String, kind: String, categoryId: String?): Boolean =
        prefs.getString(refreshKey("complete", playlistId, kind, categoryId), "false") == "true"

    fun markCatalogRefreshed(playlistId: String, kind: String, categoryId: String?, at: Long = System.currentTimeMillis()) {
        prefs.putString(refreshKey("at", playlistId, kind, categoryId), at.toString())
        prefs.putString(refreshKey("complete", playlistId, kind, categoryId), "true")
    }

    private fun refreshKey(prefix: String, playlistId: String, kind: String, categoryId: String?) =
        "catalog_${prefix}_${playlistId}_${kind}_${categoryId ?: "all"}"

    fun catalogContentCountSnapshot(playlistId: String, kind: String, includeAdult: Boolean): Int? {
        val key = countSnapshotKey("total", playlistId, kind, includeAdult)
        if (contentCountCache.containsKey(key)) return contentCountCache[key]
        return prefs.getString(key, null)?.toIntOrNull()?.takeIf { it >= 0 }
            .also { contentCountCache[key] = it }
    }

    fun saveCatalogContentCountSnapshot(playlistId: String, kind: String, includeAdult: Boolean, count: Int) {
        require(count >= 0)
        val key = countSnapshotKey("total", playlistId, kind, includeAdult)
        if (contentCountCache[key] == count) return
        contentCountCache[key] = count
        prefs.putString(key, count.toString())
    }

    fun catalogCategoryCountSnapshot(playlistId: String, kind: String, includeAdult: Boolean): Map<String, Int>? {
        val key = countSnapshotKey("categories", playlistId, kind, includeAdult)
        if (categoryCountCache.containsKey(key)) return categoryCountCache[key]
        val parsed = prefs.getString(key, null)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                buildMap {
                    json.keys().forEach { categoryId ->
                        val count = json.optInt(categoryId, -1)
                        if (categoryId.isNotBlank() && count >= 0) put(categoryId, count)
                    }
                }
            }.getOrNull()
        }
        categoryCountCache[key] = parsed
        return parsed
    }

    fun saveCatalogCategoryCountSnapshot(
        playlistId: String,
        kind: String,
        includeAdult: Boolean,
        counts: Map<String, Int>,
    ) {
        val sanitized = counts.filter { (categoryId, count) -> categoryId.isNotBlank() && count >= 0 }
        val key = countSnapshotKey("categories", playlistId, kind, includeAdult)
        if (categoryCountCache[key] == sanitized) return
        categoryCountCache[key] = sanitized
        prefs.putString(key, JSONObject(sanitized).toString())
    }

    private fun clearCatalogCountSnapshots(playlistId: String) {
        listOf("live", "movie", "series").forEach { kind ->
            listOf(false, true).forEach { includeAdult ->
                val totalKey = countSnapshotKey("total", playlistId, kind, includeAdult)
                val categoriesKey = countSnapshotKey("categories", playlistId, kind, includeAdult)
                contentCountCache.remove(totalKey)
                categoryCountCache.remove(categoriesKey)
                prefs.putString(totalKey, null)
                prefs.putString(categoriesKey, null)
            }
        }
    }

    private fun countSnapshotKey(scope: String, playlistId: String, kind: String, includeAdult: Boolean) =
        "catalog_count_${scope}_${playlistId}_${kind}_${if (includeAdult) "adult" else "safe"}"

    fun favorites(playlistId: String): Set<String> = prefs.getStringSet("favorites_$playlistId")
    fun toggleFavorite(playlistId: String, key: String): Boolean {
        val values = favorites(playlistId).toMutableSet()
        val added = if (key in values) { values.remove(key); false } else { values.add(key); true }
        prefs.putStringSet("favorites_$playlistId", values)
        return added
    }

    fun hiddenCategories(playlistId: String): Set<String> = prefs.getStringSet("hidden_$playlistId")
    fun setHiddenCategories(playlistId: String, values: Set<String>) {
        prefs.putStringSet("hidden_$playlistId", values)
    }

    private fun write(values: List<SavedPlaylist>) {
        val array = JSONArray()
        values.forEach { p -> array.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("url", p.credentials.serverUrl)
            put("username", p.credentials.username); put("password", p.credentials.password)
            put("expiry", p.expiresAt); put("status", p.status)
            put("active", p.activeConnections); put("maximum", p.maximumConnections)
            put("formats", JSONArray(p.allowedFormats))
            put("serverTimezone", p.serverTimezone)
        }) }
        persistedPlaylistCache = values
        prefs.putString("playlists", array.toString())
    }

    private fun JSONObject.toPlaylist(): SavedPlaylist? = try {
        SavedPlaylist(
            getString("id"), getString("name"),
            ProviderCredentials(getString("url"), getString("username"), getString("password")),
            optLong("expiry").takeIf { has("expiry") && !isNull("expiry") && it > 0 }, optString("status", "Unknown"),
            optInt("active").takeIf { has("active") && !isNull("active") },
            optInt("maximum").takeIf { has("maximum") && !isNull("maximum") },
            optJSONArray("formats")?.let { formats ->
                (0 until formats.length()).mapNotNull { formats.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            optString("serverTimezone").takeIf { has("serverTimezone") && !isNull("serverTimezone") && it.isNotBlank() },
        )
    } catch (_: Exception) { null }

    private companion object {
        const val LEGACY_SAVED_LOGIN_KEY = "saved_login"
    }
}
