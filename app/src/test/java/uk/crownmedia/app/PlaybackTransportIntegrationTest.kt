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

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackTransportIntegrationTest {
    @Test
    fun providerStreamsOpenThroughCurrentMedia3Transport() {
        val server = System.getenv("CROWN_TEST_SERVER").orEmpty()
        val username = System.getenv("CROWN_TEST_USERNAME").orEmpty()
        val password = System.getenv("CROWN_TEST_PASSWORD").orEmpty()
        assumeTrue(server.isNotBlank() && username.isNotBlank() && password.isNotBlank())

        listOf(
            "$server/live/$username/$password/4916534.m3u8",
            "$server/live/$username/$password/4555001.m3u8",
            // This provider item currently rejects HLS but remains playable over TS,
            // exercising the same automatic fallback used by the application.
            "$server/live/$username/$password/4544652.ts",
            "$server/movie/$username/$password/4939141.mkv",
            "$server/movie/$username/$password/4939140.mkv",
            "$server/movie/$username/$password/4939139.mp4",
            "$server/series/$username/$password/4938575.mkv",
            "$server/series/$username/$password/4916867.mkv",
            "$server/series/$username/$password/4916410.mkv",
        ).forEach { url ->
            val source = OkHttpDataSource.Factory(OkHttpClient.Builder().followRedirects(true).build())
                .setUserAgent("CrownMedia/1.0")
                .createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(Uri.parse(url)).setLength(564).build())
                val buffer = ByteArray(564)
                assertTrue(source.read(buffer, 0, buffer.size) > 0)
            } finally {
                source.close()
            }
        }
    }
}
