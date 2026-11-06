package com.globalvpn.app

data class Country(
    val code: String,     // short code used in file names: us, jp, uk ...
    val iso: String,      // ISO 3166 code returned by geo-IP services
    val flag: String,
    val nameZh: String,
    val nameEn: String,
)

object Countries {
    val ALL: List<Country> = listOf(
        Country("us", "US", "🇺🇸", "美國", "United States"),
        Country("jp", "JP", "🇯🇵", "日本", "Japan"),
        Country("de", "DE", "🇩🇪", "德國", "Germany"),
        Country("fr", "FR", "🇫🇷", "法國", "France"),
        Country("se", "SE", "🇸🇪", "瑞典", "Sweden"),
        Country("uk", "GB", "🇬🇧", "英國", "United Kingdom"),
        Country("fi", "FI", "🇫🇮", "芬蘭", "Finland"),
        Country("es", "ES", "🇪🇸", "西班牙", "Spain"),
        Country("it", "IT", "🇮🇹", "義大利", "Italy"),
        Country("no", "NO", "🇳🇴", "挪威", "Norway"),
    )

    fun byCode(code: String?): Country? = ALL.firstOrNull { it.code == code }

    fun byIso(iso: String?): Country? =
        ALL.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /** Guess the country from a file name such as "jp-phone.conf" or "Germany.conf". */
    fun guessFromFileName(fileName: String): Country? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val lower = base.lowercase()
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for (t in tokens) {
            ALL.firstOrNull { it.code == t || it.iso.lowercase() == t }?.let { return it }
        }
        ALL.firstOrNull { lower.contains(it.nameEn.lowercase().replace(" ", "")) ||
                lower.contains(it.nameEn.lowercase()) }?.let { return it }
        return ALL.firstOrNull { base.contains(it.nameZh) }
    }
}
