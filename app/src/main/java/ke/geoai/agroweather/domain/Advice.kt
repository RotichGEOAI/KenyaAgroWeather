package ke.geoai.agroweather.domain

/** Traffic-light status. The UI always pairs colour with a text badge (colour-blind safe). */
enum class Light { GREEN, AMBER, RED }

data class Advice(
    val light: Light,
    val badge: String,
    val headline: String,
    val details: List<String> = emptyList(),
)
