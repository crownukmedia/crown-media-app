package uk.crownmedia.app

/** Provider endpoints are intentionally kept out of the login UI. */
enum class CrownService(
    val displayName: String,
    val serverUrl: String?,
) {
    PREMIUM("Crown Premium", BuildConfig.CROWN_PREMIUM_URL),
    PRO("Crown Pro", null),
    EIGHT_K("Crown 8K", null),
    ;

    val isAvailable: Boolean get() = serverUrl != null

    companion object {
        val default = PREMIUM
        val displayNames: List<String> = entries.map(CrownService::displayName)

        fun fromDisplayName(value: String): CrownService =
            entries.firstOrNull { it.displayName == value } ?: default

        fun fromStoredValue(value: String?): CrownService =
            entries.firstOrNull { it.name == value } ?: default
    }
}
