package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsageAnalyticsTest {
    private val context
        get() = RuntimeEnvironment.getApplication() as CrownMediaApplication

    @Before
    fun clearConsent() {
        context.getSharedPreferences("usage_analytics", 0).edit().clear().commit()
    }

    @Test
    fun missingFirebaseConfigurationIsSafeAndCollectsNothing() {
        val analytics = UsageAnalytics.create(context)

        assertFalse(analytics.isConfigured)
        assertFalse(analytics.isEnabled)
        assertNull(analytics.consentDecision)

        analytics.updateConsent(true)
        analytics.trackScreen("home")

        assertFalse(analytics.isEnabled)
        assertEquals(true, analytics.consentDecision)
    }
}
