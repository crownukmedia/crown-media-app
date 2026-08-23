package uk.crownmedia.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderNetworkSecurityTest {
    @Test
    fun providerRedirectsRemainPlayableWhenTheEdgeHostChanges() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config)
        var globalCleartext = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "base-config" -> globalCleartext = parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", false)
                }
            }
            parser.next()
        }

        assertTrue(globalCleartext)
    }
}
