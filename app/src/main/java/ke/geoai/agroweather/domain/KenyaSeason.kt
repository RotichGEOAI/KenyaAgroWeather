package ke.geoai.agroweather.domain

/** Broad national rainfall calendar. Local timing varies by region (e.g. the western highlands stay wetter for longer). */
object KenyaSeason {
    data class Info(val name: String, val note: String)

    fun of(month: Int): Info = when (month) {
        3, 4, 5 -> Info("Long rains (Mar–May)", "Main planting season for most of Kenya.")
        10, 11, 12 -> Info("Short rains (Oct–Dec)", "Second planting season; the one most affected by El Niño and La Niña.")
        1, 2 -> Info("Short dry season (Jan–Feb)", "Harvest, drying and land preparation; irrigate dry-season crops.")
        else -> Info("Cool, mostly dry season (Jun–Sep)", "Rain continues in the western highlands and near the coast.")
    }
}
