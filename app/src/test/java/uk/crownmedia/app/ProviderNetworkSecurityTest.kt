package uk.crownmedia.app

import org.junit.Assert.assertFalse
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
    fun providerApiAndRedirectCdnAreAllowedWithoutEnablingGlobalCleartext() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config)
        val domains = mutableSetOf<String>()
        var globalCleartext = true
        var providerCleartext = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "base-config" -> globalCleartext = parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", true)
                    "domain-config" -> providerCleartext = parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", false)
                    "domain" -> domains += parser.nextText().trim()
                }
            }
            parser.next()
        }

        assertFalse(globalCleartext)
        assertTrue(providerCleartext)
        assertTrue("novixa.uk" in domains)
        assertTrue("223andyfire.uk" in domains)
    }
}
