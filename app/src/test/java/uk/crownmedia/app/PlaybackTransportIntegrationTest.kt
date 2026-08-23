package uk.crownmedia.app

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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

        val candidates = mapOf(
            "Live" to listOf(
                "$server/live/$username/$password/4916534.ts",
                "$server/live/$username/$password/4555001.ts",
                "$server/live/$username/$password/4544652.ts",
            ),
            "Movie" to listOf(
                "$server/movie/$username/$password/4939141.mkv",
                "$server/movie/$username/$password/4939140.mkv",
                "$server/movie/$username/$password/4939139.mp4",
            ),
            "Episode" to listOf(
                "$server/series/$username/$password/4938575.mkv",
                "$server/series/$username/$password/4916867.mkv",
                "$server/series/$username/$password/4916410.mkv",
            ),
        )

        candidates.forEach { (kind, urls) ->
            assertTrue("No readable $kind candidate", urls.any(::canRead))
        }
    }

    private fun canRead(url: String): Boolean {
        val source = OkHttpDataSource.Factory(OkHttpClient.Builder().followRedirects(true).build())
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
}
