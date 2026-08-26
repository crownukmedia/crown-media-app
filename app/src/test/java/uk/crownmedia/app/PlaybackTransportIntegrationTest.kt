package uk.crownmedia.app

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uk.crownmedia.core.model.ProviderCredentials
import uk.crownmedia.data.xtream.XtreamClient
import uk.crownmedia.data.xtream.preferredLiveExtension
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackTransportIntegrationTest {
    @Test
    fun providerStreamsOpenThroughCurrentMedia3Transport() {
        val server = System.getenv("CROWN_TEST_SERVER").orEmpty()
        val username = System.getenv("CROWN_TEST_USERNAME").orEmpty()
        val password = System.getenv("CROWN_TEST_PASSWORD").orEmpty()
        assumeTrue(server.isNotBlank() && username.isNotBlank() && password.isNotBlank())

        val credentials = ProviderCredentials(server, username, password)
        val candidates = runBlocking { currentProviderCandidates(credentials) }

        candidates.forEach { (kind, urls) ->
            assertTrue("No readable $kind candidate", urls.any(::canRead))
        }
    }

    private suspend fun currentProviderCandidates(credentials: ProviderCredentials): Map<String, List<String>> {
        val api = XtreamClient()
        val account = api.authenticate(credentials)
        val liveItems = api.catalogBatches(credentials, "live", batchSize = DISCOVERY_BATCH_SIZE).first()
        val movieItems = api.catalogBatches(credentials, "movie", batchSize = DISCOVERY_BATCH_SIZE).first()
        val seriesItems = api.catalogBatches(credentials, "series", batchSize = DISCOVERY_BATCH_SIZE).first()
        val episodes = mutableListOf<Pair<String, String>>()
        for (series in evenlySample(seriesItems, SERIES_DISCOVERY_LIMIT)) {
            val details = runCatching { api.seriesInfo(credentials, series.id) }.getOrNull() ?: continue
            details.episodes.values.flatten().firstOrNull()?.let { episodes += it.id to it.extension }
            if (episodes.size >= TRANSPORT_CANDIDATE_LIMIT) break
        }
        val liveExtension = preferredLiveExtension(account.allowedFormats)
        return mapOf(
            "Live" to evenlySample(liveItems, TRANSPORT_CANDIDATE_LIMIT).map {
                api.streamUrl(credentials, "live", it.id, liveExtension)
            },
            "Movie" to evenlySample(movieItems, TRANSPORT_CANDIDATE_LIMIT).map {
                api.streamUrl(credentials, "movie", it.id, it.extension ?: "mp4")
            },
            "Episode" to episodes.map { (id, extension) ->
                api.streamUrl(credentials, "episode", id, extension)
            },
        )
    }

    private fun <T> evenlySample(values: List<T>, limit: Int): List<T> {
        if (values.size <= limit) return values
        return List(limit) { index -> values[index * values.size / limit] }
    }

    private fun canRead(url: String): Boolean {
        val source = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build(),
        )
            .setUserAgent("CrownMedia/1.0")
            .createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse(url)).setLength(564).build())
            source.read(ByteArray(564), 0, 564) > 0
        } catch (_: IOException) {
            false
        } finally {
            source.close()
        }
    }

    companion object {
        private const val DISCOVERY_BATCH_SIZE = 240
        private const val SERIES_DISCOVERY_LIMIT = 12
        private const val TRANSPORT_CANDIDATE_LIMIT = 12
    }
}
