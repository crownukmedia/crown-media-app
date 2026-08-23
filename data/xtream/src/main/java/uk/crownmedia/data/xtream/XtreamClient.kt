package uk.crownmedia.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.squareup.moshi.JsonReader
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uk.crownmedia.core.model.AccountStatus
import uk.crownmedia.core.model.ProviderCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class XtreamAccount(
    val status: AccountStatus,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val allowedFormats: List<String>,
    val serverTimezone: String?,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamItem(
    val id: String,
    val categoryId: String,
    val name: String,
    val imageUrl: String?,
    val rating: String?,
    val addedEpochSeconds: Long?,
    val extension: String?,
    val epgChannelId: String?,
    val catchUp: Boolean,
    val catchUpDays: Int,
    val plot: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val trailer: String? = null,
    val providerOrder: Int? = null,
    val isAdult: Boolean = false,
)

data class XtreamProgramme(
    val title: String,
    val description: String?,
    val start: String?,
    val end: String?,
    val startTimestamp: Long?,
    val stopTimestamp: Long?,
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val extension: String,
    val imageUrl: String?,
    val plot: String?,
    val duration: String?,
)

data class XtreamSeriesDetails(
    val name: String,
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val cast: String?,
    val genre: String?,
    val rating: String?,
    val trailer: String?,
    val episodes: Map<Int, List<XtreamEpisode>>,
)

/** Clean-room client for the documented Xtream Codes compatible player_api.php surface. */
class XtreamClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    suspend fun authenticate(credentials: ProviderCredentials): XtreamAccount = withContext(Dispatchers.IO) {
        val root = objectCall(credentials, null)
        val user = root.optJSONObject("user_info") ?: throw IOException("Missing user_info")
        val auth = user.flexInt("auth")
        if (auth != 1) throw SecurityException(user.optString("message", "Invalid credentials"))
        val statusText = user.optString("status").lowercase()
        val status = when (statusText) {
            "active" -> AccountStatus.ACTIVE
            "expired" -> AccountStatus.EXPIRED
            "disabled", "banned" -> AccountStatus.DISABLED
            else -> AccountStatus.UNKNOWN
        }
        val formats = user.optJSONArray("allowed_output_formats")?.strings().orEmpty()
        XtreamAccount(
            status = status,
            expiresAtEpochSeconds = user.flexLong("exp_date"),
            activeConnections = user.flexIntOrNull("active_cons"),
            maximumConnections = user.flexIntOrNull("max_connections"),
            allowedFormats = formats,
            serverTimezone = root.optJSONObject("server_info")?.optString("timezone")?.takeIf(String::isNotBlank),
        )
    }

    suspend fun categories(credentials: ProviderCredentials, kind: String): List<XtreamCategory> =
        withContext(Dispatchers.IO) { arrayCall(credentials, "get_${kind}_categories").objects().mapNotNull {
            val id = it.flexString("category_id") ?: return@mapNotNull null
            XtreamCategory(id, it.optString("category_name", "Uncategorized"))
        } }

    suspend fun liveStreams(credentials: ProviderCredentials, categoryId: String? = null): List<XtreamItem> =
        withContext(Dispatchers.IO) { streamItems(arrayCall(credentials, "get_live_streams", categoryId), "stream_id", "stream_icon") }

    suspend fun movies(credentials: ProviderCredentials, categoryId: String? = null): List<XtreamItem> =
        withContext(Dispatchers.IO) { streamItems(arrayCall(credentials, "get_vod_streams", categoryId), "stream_id", "stream_icon") }

    suspend fun series(credentials: ProviderCredentials, categoryId: String? = null): List<XtreamItem> =
        withContext(Dispatchers.IO) { streamItems(arrayCall(credentials, "get_series", categoryId), "series_id", "cover") }

    /**
     * Streams large provider catalogs in bounded batches. Unlike the legacy list calls this avoids
     * retaining the response string, JSONArray, JSONObjects and mapped item list at the same time.
     */
    fun catalogBatches(
        credentials: ProviderCredentials,
        kind: String,
        categoryId: String? = null,
        batchSize: Int = 60,
    ): Flow<List<XtreamItem>> = flow {
        require(batchSize > 0)
        val action = when (kind) {
            "live" -> "get_live_streams"
            "movie" -> "get_vod_streams"
            "series" -> "get_series"
            else -> error("Unsupported catalog kind: $kind")
        }
        val idKey = if (kind == "series") "series_id" else "stream_id"
        val imageKey = if (kind == "series") "cover" else "stream_icon"
        val call = http.newCall(apiRequest(credentials, action, categoryId?.let { mapOf("category_id" to it) }.orEmpty()))
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("Empty server response")
                JsonReader.of(source).use { reader ->
                    val batch = ArrayList<XtreamItem>(batchSize)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readStreamItem(reader, idKey, imageKey)?.let(batch::add)
                        if (batch.size == batchSize) {
                            emit(batch.toList())
                            batch.clear()
                        }
                    }
                    reader.endArray()
                    if (batch.isNotEmpty()) emit(batch.toList())
                }
            }
        } finally {
            cancellation.dispose()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun movieInfo(credentials: ProviderCredentials, id: String): XtreamItem = withContext(Dispatchers.IO) {
        val root = objectCall(credentials, "get_vod_info", mapOf("vod_id" to id))
        val info = root.optJSONObject("info") ?: JSONObject()
        val movie = root.optJSONObject("movie_data") ?: JSONObject()
        XtreamItem(
            id = id,
            categoryId = movie.flexString("category_id").orEmpty(),
            name = info.optString("name", movie.optString("name", "Movie")),
            imageUrl = info.optString("movie_image", movie.optString("stream_icon")).nullIfBlank(),
            rating = info.flexString("rating"),
            addedEpochSeconds = movie.flexLong("added"),
            extension = movie.optString("container_extension", "mp4"),
            epgChannelId = null, catchUp = false, catchUpDays = 0,
            plot = info.optString("plot").nullIfBlank(), year = info.flexString("releasedate") ?: info.flexString("year"),
            duration = info.flexString("duration"), cast = info.flexString("cast"), director = info.flexString("director"),
            genre = info.flexString("genre"), trailer = info.flexString("youtube_trailer"),
        )
    }

    suspend fun seriesInfo(credentials: ProviderCredentials, id: String): XtreamSeriesDetails = withContext(Dispatchers.IO) {
        val root = objectCall(credentials, "get_series_info", mapOf("series_id" to id))
        val info = root.optJSONObject("info") ?: JSONObject()
        val episodeMap = linkedMapOf<Int, List<XtreamEpisode>>()
        val episodes = root.optJSONObject("episodes") ?: JSONObject()
        episodes.keys().forEach { seasonKey ->
            val season = seasonKey.toIntOrNull() ?: return@forEach
            episodeMap[season] = (episodes.optJSONArray(seasonKey) ?: JSONArray()).objects().mapNotNull { e ->
                val episodeId = e.flexString("id") ?: return@mapNotNull null
                val meta = e.optJSONObject("info") ?: JSONObject()
                XtreamEpisode(
                    episodeId, e.flexIntOrNull("season") ?: season, e.flexIntOrNull("episode_num") ?: 0,
                    e.optString("title", "Episode"), e.optString("container_extension", "mp4"),
                    meta.optString("movie_image").nullIfBlank(), meta.optString("plot").nullIfBlank(), meta.flexString("duration"),
                )
            }
        }
        val backdrops = info.optJSONArray("backdrop_path")?.strings().orEmpty()
        XtreamSeriesDetails(
            info.optString("name", "Series"), info.optString("plot").nullIfBlank(), info.optString("cover").nullIfBlank(),
            backdrops.firstOrNull(), info.flexString("cast"), info.flexString("genre"), info.flexString("rating"),
            info.flexString("youtube_trailer"), episodeMap,
        )
    }

    suspend fun shortEpg(credentials: ProviderCredentials, streamId: String, limit: Int = 8): List<XtreamProgramme> = withContext(Dispatchers.IO) {
        val root = objectCall(credentials, "get_short_epg", mapOf("stream_id" to streamId, "limit" to limit.toString()))
        (root.optJSONArray("epg_listings") ?: JSONArray()).objects().map { e ->
            XtreamProgramme(
                decodeBase64(e.optString("title")), decodeBase64(e.optString("description")).nullIfBlank(),
                e.optString("start").nullIfBlank(), e.optString("end").nullIfBlank(),
                e.flexLong("start_timestamp"), e.flexLong("stop_timestamp"),
            )
        }
    }

    fun streamUrl(credentials: ProviderCredentials, kind: String, id: String, extension: String? = null): String {
        val base = normalizeServerUrl(credentials.serverUrl)
        val segment = when (kind) {
            "live" -> "live"
            "movie" -> "movie"
            "episode" -> "series"
            else -> throw IllegalArgumentException("Unsupported playable content kind: $kind")
        }
        val streamId = id.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Stream ID is required")
        val defaultExtension = if (kind == "live") "ts" else "mp4"
        val ext = extension?.trim()?.trimStart('.')?.takeIf { it.matches(STREAM_EXTENSION) } ?: defaultExtension
        return "$base/$segment/${credentials.username.encodePath()}/${credentials.password.encodePath()}/${streamId.encodePath()}.$ext"
    }

    /**
     * Reads only the beginning of a live transport stream. This is intentionally bounded: it does
     * not start playback, download a segment, or walk an HLS redirect chain.
     */
    suspend fun probeLiveStream(credentials: ProviderCredentials, id: String, extension: String = "ts"): Boolean = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(streamUrl(credentials, "live", id, extension))
            .header("User-Agent", "CrownMedia/1.0")
            .header("Range", "bytes=0-563")
            .header("Connection", "close")
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(8, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val available = response.use {
                    if (!response.isSuccessful) return@use false
                    val input = response.body?.byteStream() ?: return@use false
                    input.use {
                        val sample = ByteArray(if (extension.equals("m3u8", true)) 2048 else 564)
                        var total = 0
                        while (total < sample.size) {
                            val read = input.read(sample, total, sample.size - total)
                            if (read <= 0) break
                            total += read
                        }
                        if (extension.equals("m3u8", true)) {
                            total >= 7 && String(sample, 0, total, Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
                        } else {
                            total >= 188 && sample[0] == 0x47.toByte()
                        }
                    }
                }
                if (continuation.isActive) continuation.resume(available)
            }
        })
    }

    fun catchUpUrl(credentials: ProviderCredentials, id: String, start: String, durationMinutes: Int): String {
        val base = normalizeServerUrl(credentials.serverUrl)
        return "$base/timeshift/${credentials.username.encodePath()}/${credentials.password.encodePath()}/$durationMinutes/${start.encodePath()}/$id.ts"
    }

    private suspend fun objectCall(credentials: ProviderCredentials, action: String?, extra: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(call(credentials, action, extra))

    private suspend fun arrayCall(credentials: ProviderCredentials, action: String, categoryId: String? = null): JSONArray =
        JSONArray(call(credentials, action, categoryId?.let { mapOf("category_id" to it) }.orEmpty()))

    private suspend fun call(credentials: ProviderCredentials, action: String?, extra: Map<String, String>): String =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(apiRequest(credentials, action, extra))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching {
                        response.use {
                            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
                            response.body?.string() ?: throw IOException("Empty server response")
                        }
                    }.onSuccess { body ->
                        if (continuation.isActive) continuation.resume(body)
                    }.onFailure { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private fun apiRequest(credentials: ProviderCredentials, action: String?, extra: Map<String, String>): Request {
        val base = normalizeServerUrl(credentials.serverUrl)
        val builder = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
        if (action != null) builder.addQueryParameter("action", action)
        extra.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return Request.Builder().url(builder.build()).header("User-Agent", "CrownMedia/1.0").build()
    }

    private fun readStreamItem(reader: JsonReader, idKey: String, imageKey: String): XtreamItem? {
        var id: String? = null
        var categoryId = ""
        var name = "Untitled"
        var imageUrl: String? = null
        var rating: String? = null
        var added: Long? = null
        var extension: String? = null
        var epgChannelId: String? = null
        var catchUp = false
        var catchUpDays = 0
        var providerOrder: Int? = null
        var isAdult = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                idKey -> id = reader.scalarString()
                "category_id" -> categoryId = reader.scalarString().orEmpty()
                "name" -> name = reader.scalarString().nullIfBlank() ?: "Untitled"
                imageKey -> imageUrl = reader.scalarString().nullIfBlank()
                "rating" -> rating = reader.scalarString().nullIfBlank()
                "added" -> added = reader.scalarString()?.toLongOrNull()?.takeIf { it > 0 }
                "container_extension" -> extension = reader.scalarString().nullIfBlank()
                "epg_channel_id" -> epgChannelId = reader.scalarString().nullIfBlank()
                "tv_archive" -> catchUp = reader.scalarString() == "1"
                "tv_archive_duration" -> catchUpDays = reader.scalarString()?.toIntOrNull() ?: 0
                "num" -> providerOrder = reader.scalarString()?.toIntOrNull()
                "is_adult" -> isAdult = reader.scalarString() == "1"
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return id.nullIfBlank()?.let {
            XtreamItem(it, categoryId, name, imageUrl, rating, added, extension, epgChannelId, catchUp, catchUpDays, providerOrder = providerOrder, isAdult = isAdult)
        }
    }

    private fun streamItems(array: JSONArray, idKey: String, imageKey: String): List<XtreamItem> = array.objects().mapNotNull { item ->
        val id = item.flexString(idKey) ?: return@mapNotNull null
        XtreamItem(
            id, item.flexString("category_id").orEmpty(), item.optString("name", "Untitled"),
            item.optString(imageKey).nullIfBlank(), item.flexString("rating"), item.flexLong("added"),
            item.optString("container_extension").nullIfBlank(), item.optString("epg_channel_id").nullIfBlank(),
            item.flexInt("tv_archive") == 1, item.flexIntOrNull("tv_archive_duration") ?: 0,
            providerOrder = item.flexIntOrNull("num"), isAdult = item.flexInt("is_adult") == 1,
        )
    }
}

private fun JsonReader.scalarString(): String? = when (peek()) {
    JsonReader.Token.NULL -> nextNull<Unit>().let { null }
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> nextString()
    JsonReader.Token.BOOLEAN -> nextBoolean().toString()
    else -> skipValue().let { null }
}

fun normalizeServerUrl(value: String): String {
    var url = value.trim().trimEnd('/')
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
    val parsed = url.toHttpUrl()
    return parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
}

fun preferredLiveExtension(allowedFormats: List<String>): String {
    val normalized = allowedFormats.map { it.trim().trimStart('.').lowercase() }
    return when {
        "m3u8" in normalized -> "m3u8"
        "ts" in normalized -> "ts"
        else -> "ts"
    }
}

private fun String.encodePath(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
private val STREAM_EXTENSION = Regex("[A-Za-z0-9]{1,12}")
private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() && it != "null" }
private fun JSONObject.flexString(key: String): String? = if (!has(key) || isNull(key)) null else opt(key)?.toString().nullIfBlank()
private fun JSONObject.flexInt(key: String): Int = flexIntOrNull(key) ?: 0
private fun JSONObject.flexIntOrNull(key: String): Int? = flexString(key)?.toIntOrNull()
private fun JSONObject.flexLong(key: String): Long? = flexString(key)?.toLongOrNull()?.takeIf { it > 0 }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).nullIfBlank() }
private fun decodeBase64(value: String): String = try {
    val decoded = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
    String(decoded, Charsets.UTF_8).takeIf { it.any(Char::isLetterOrDigit) } ?: value
} catch (_: IllegalArgumentException) { value }
