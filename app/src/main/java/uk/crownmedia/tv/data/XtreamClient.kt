package uk.crownmedia.tv.data

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import uk.crownmedia.tv.config.CrownConfig
import uk.crownmedia.tv.model.ContentCategory
import uk.crownmedia.tv.model.EpgEntry
import uk.crownmedia.tv.model.LiveStream
import uk.crownmedia.tv.model.ProviderCredentials
import uk.crownmedia.tv.model.ProviderSession
import uk.crownmedia.tv.model.SeriesDetail
import uk.crownmedia.tv.model.SeriesEpisode
import uk.crownmedia.tv.model.SeriesItem
import uk.crownmedia.tv.model.VodDetail
import uk.crownmedia.tv.model.VodStream
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class XtreamClient(
    private val portalUrl: String = CrownConfig.portalUrl,
) {
    suspend fun authenticate(credentials: ProviderCredentials): ProviderSession {
        val root = requestObject(credentials)
        val userInfo = root.getJSONObject("user_info")
        val allowedFormats = userInfo.optJSONArray("allowed_output_formats").toStringList()
        val auth = userInfo.optInt("auth", 0)
        if (auth != 1) {
            throw IllegalStateException("Login failed. Check the username and password.")
        }

        return ProviderSession(
            credentials = credentials,
            status = userInfo.optString("status", "Unknown"),
            expiryEpochSeconds = userInfo.optString("exp_date").toLongOrNull(),
            maxConnections = userInfo.optString("max_connections").toIntOrNull() ?: 1,
            allowedFormats = allowedFormats,
        )
    }

    suspend fun liveCategories(credentials: ProviderCredentials): List<ContentCategory> =
        requestArray(credentials, action = "get_live_categories").toCategories()

    suspend fun vodCategories(credentials: ProviderCredentials): List<ContentCategory> =
        requestArray(credentials, action = "get_vod_categories").toCategories()

    suspend fun seriesCategories(credentials: ProviderCredentials): List<ContentCategory> =
        requestArray(credentials, action = "get_series_categories").toCategories()

    suspend fun liveStreams(credentials: ProviderCredentials, categoryId: String): List<LiveStream> =
        requestArray(
            credentials,
            action = "get_live_streams",
            extras = mapOf("category_id" to categoryId),
        ).mapArray { json ->
            LiveStream(
                id = json.optString("stream_id"),
                name = json.optString("name"),
                iconUrl = json.optString("stream_icon").ifBlank { null },
                epgChannelId = json.optString("epg_channel_id").ifBlank { null },
                categoryId = json.optString("category_id"),
                hasCatchup = json.optInt("tv_archive", 0) == 1,
                catchupWindowHours = json.optString("tv_archive_duration").toIntOrNull() ?: 0,
            )
        }

    suspend fun vodStreams(credentials: ProviderCredentials, categoryId: String): List<VodStream> =
        requestArray(
            credentials,
            action = "get_vod_streams",
            extras = mapOf("category_id" to categoryId),
        ).mapArray { json ->
            VodStream(
                id = json.optString("stream_id"),
                name = json.optString("name"),
                iconUrl = json.optString("stream_icon").ifBlank { null },
                rating = json.optString("rating").ifBlank { null },
                categoryId = json.optString("category_id"),
                extension = json.optString("container_extension", "mp4"),
            )
        }

    suspend fun vodDetail(credentials: ProviderCredentials, streamId: String): VodDetail {
        val root = requestObject(
            credentials,
            action = "get_vod_info",
            extras = mapOf("vod_id" to streamId),
        )
        val info = root.optJSONObject("info") ?: JSONObject()
        val movieData = root.optJSONObject("movie_data") ?: JSONObject()
        return VodDetail(
            stream = VodStream(
                id = streamId,
                name = movieData.optString("name").ifBlank { info.optString("name") },
                iconUrl = movieData.optString("stream_icon").ifBlank {
                    info.optString("movie_image").ifBlank { null }
                },
                rating = info.optString("rating").ifBlank { null },
                categoryId = movieData.optString("category_id"),
                extension = movieData.optString("container_extension", "mp4"),
            ),
            plot = info.optString("plot").ifBlank { null },
            genre = info.optString("genre").ifBlank { null },
            cast = info.optString("cast").ifBlank { null },
            durationLabel = info.optString("duration").ifBlank { null },
            releaseDate = info.optString("releasedate").ifBlank { null },
            backdropUrl = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null },
        )
    }

    suspend fun series(credentials: ProviderCredentials, categoryId: String? = null): List<SeriesItem> =
        requestArray(
            credentials,
            action = "get_series",
            extras = categoryId?.let { mapOf("category_id" to it) }.orEmpty(),
        ).mapArray { json ->
            val backdrops = json.optJSONArray("backdrop_path")
            SeriesItem(
                id = json.optString("series_id"),
                name = json.optString("name"),
                coverUrl = json.optString("cover").ifBlank { null },
                backdropUrl = backdrops?.optString(0)?.ifBlank { null },
                plot = json.optString("plot").ifBlank { null },
                rating = json.optString("rating").ifBlank { null },
                categoryId = json.optString("category_id"),
            )
        }

    suspend fun seriesDetail(credentials: ProviderCredentials, seriesId: String): SeriesDetail {
        val root = requestObject(
            credentials,
            action = "get_series_info",
            extras = mapOf("series_id" to seriesId),
        )
        val info = root.getJSONObject("info")
        val episodes = mutableMapOf<Int, List<SeriesEpisode>>()
        val episodeRoot = root.optJSONObject("episodes") ?: JSONObject()

        episodeRoot.keys().forEach { seasonKey ->
            val seasonNumber = seasonKey.toIntOrNull() ?: return@forEach
            val seasonEpisodes = episodeRoot.optJSONArray(seasonKey).mapArray { json ->
                val meta = json.optJSONObject("info") ?: JSONObject()
                SeriesEpisode(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    seasonNumber = json.optInt("season", seasonNumber),
                    episodeNumber = json.optInt("episode_num"),
                    plot = meta.optString("plot").ifBlank { null },
                    posterUrl = meta.optString("movie_image").ifBlank { null },
                    durationLabel = meta.optString("duration").ifBlank { null },
                    extension = json.optString("container_extension", "mkv"),
                )
            }
            episodes[seasonNumber] = seasonEpisodes
        }

        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }
        return SeriesDetail(
            item = SeriesItem(
                id = seriesId,
                name = info.optString("name"),
                coverUrl = info.optString("cover").ifBlank { null },
                backdropUrl = backdrop,
                plot = info.optString("plot").ifBlank { null },
                rating = info.optString("rating").ifBlank { null },
                categoryId = info.optString("category_id"),
            ),
            cast = info.optString("cast").ifBlank { null },
            genre = info.optString("genre").ifBlank { null },
            episodesBySeason = episodes.toSortedMap(),
        )
    }

    suspend fun shortEpg(credentials: ProviderCredentials, streamId: String, limit: Int = 8): List<EpgEntry> =
        requestObject(
            credentials,
            action = "get_short_epg",
            extras = mapOf("stream_id" to streamId, "limit" to limit.toString()),
        ).optJSONArray("epg_listings").mapArray { json ->
            EpgEntry(
                id = json.optString("id"),
                title = decodeBase64(json.optString("title")),
                description = decodeBase64(json.optString("description")).ifBlank { null },
                start = json.optString("start"),
                end = json.optString("end"),
                startEpochSeconds = json.optString("start_timestamp").toLongOrNull(),
                endEpochSeconds = json.optString("stop_timestamp").toLongOrNull(),
            )
        }

    fun buildLiveUrl(session: ProviderSession, streamId: String): String =
        "$portalUrl/live/${session.credentials.username}/${session.credentials.password}/$streamId.${session.preferredLiveFormat}"

    fun buildMovieUrl(session: ProviderSession, streamId: String, extension: String): String =
        "$portalUrl/movie/${session.credentials.username}/${session.credentials.password}/$streamId.$extension"

    fun buildEpisodeUrl(session: ProviderSession, episodeId: String, extension: String): String =
        "$portalUrl/series/${session.credentials.username}/${session.credentials.password}/$episodeId.$extension"

    fun buildCatchupUrl(
        session: ProviderSession,
        streamId: String,
        entry: EpgEntry,
    ): String? {
        val startEpoch = entry.startEpochSeconds ?: return null
        val endEpoch = entry.endEpochSeconds ?: return null
        val durationMinutes = Duration.ofSeconds(endEpoch - startEpoch).toMinutes().coerceAtLeast(1)
        val startValue = LocalDateTime.ofEpochSecond(startEpoch, 0, ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
        return "$portalUrl/timeshift/${session.credentials.username}/${session.credentials.password}/$durationMinutes/$startValue/$streamId.ts"
    }

    private suspend fun requestArray(
        credentials: ProviderCredentials,
        action: String,
        extras: Map<String, String> = emptyMap(),
    ): JSONArray = JSONArray(request(credentials, action, extras))

    private suspend fun requestObject(
        credentials: ProviderCredentials,
        action: String? = null,
        extras: Map<String, String> = emptyMap(),
    ): JSONObject = JSONObject(request(credentials, action, extras))

    private suspend fun request(
        credentials: ProviderCredentials,
        action: String?,
        extras: Map<String, String>,
    ): String {
        val builder = Uri.parse("$portalUrl/player_api.php").buildUpon()
            .appendQueryParameter("username", credentials.username)
            .appendQueryParameter("password", credentials.password)

        if (action != null) {
            builder.appendQueryParameter("action", action)
        }
        extras.forEach { (key, value) -> builder.appendQueryParameter(key, value) }

        val connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Crown Media TV) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36",
            )
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }

        return connection.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun JSONArray.toCategories(): List<ContentCategory> = mapArray { json ->
        ContentCategory(
            id = json.optString("category_id"),
            name = json.optString("category_name"),
        )
    }

    private fun decodeBase64(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT)).trim()
        }.getOrElse { value }
    }
}

private inline fun <T> JSONArray?.mapArray(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            add(transform(getJSONObject(index)))
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }
}
