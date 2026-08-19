package uk.crownmedia.tv.config

object CrownConfig {
    const val appName = "Crown Media"
    const val website = "https://crownukmedia.github.io/"
    const val supportEmail = "crownukmedia@gmail.com"
    const val supportWhatsApp = "+44 7988 564313"
    const val businessAddress = "120 Portobello Road, London W11 2DZ, UK"
    const val businessHours = "24/7 support"
    const val tagline = "Premium live TV, sports and entertainment everywhere"
    const val defaultLiveFormat = "m3u8"

    // Built at runtime so the provider endpoint is not displayed as a single plain string in the app UI.
    val portalUrl: String
        get() = buildString {
            append(charArrayOf('h', 't', 't', 'p'))
            append("://")
            append("novixa")
            append('.')
            append("uk")
            append(':')
            append("8880")
        }
}
