package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun analyticsCollectionFollowsFirebaseAvailabilityAndPreference() {
        val analytics = UsageAnalytics.create(context)

        assertEquals(true, analytics.consentDecision)
        assertEquals(analytics.isConfigured, analytics.isEnabled)

        analytics.updateConsent(false)
        analytics.trackScreen("home")

        assertFalse(analytics.isEnabled)
        assertEquals(false, analytics.consentDecision)
    }

    @Test
    fun analyticsDefaultsOnAndPersistsUserOptOut() {
        val analytics = UsageAnalytics.create(context)

        assertEquals(true, analytics.consentDecision)

        analytics.updateConsent(false)

        assertEquals(false, UsageAnalytics.create(context).consentDecision)
        assertFalse(UsageAnalytics.create(context).isEnabled)

        analytics.updateConsent(true)

        assertTrue(UsageAnalytics.create(context).consentDecision == true)
    }
}
