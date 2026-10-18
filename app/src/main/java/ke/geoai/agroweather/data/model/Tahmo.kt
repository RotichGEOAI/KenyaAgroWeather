package ke.geoai.agroweather.data.model

import java.time.LocalDate
import java.time.LocalDateTime

data class TahmoStation(val code: String, val name: String?, val lat: Double, val lon: Double)

/** One quality-controlled rain record (mm accumulated in the record's interval), on the farm's local clock. */
data class TahmoRainRecord(val time: LocalDateTime, val mm: Double)

/** Nearest TAHMO station and what it recorded recently. */
data class TahmoRain(
    val station: TahmoStation,
    val distanceKm: Double,
    val records: List<TahmoRainRecord>,
) {
    /** Rain recorded on [date] before [untilHour] (24 = the whole day). */
    fun mmFor(date: LocalDate, untilHour: Int = 24): Double =
        records.filter { it.time.toLocalDate() == date && it.time.hour < untilHour }.sumOf { it.mm }

    fun dailyMm(): Map<LocalDate, Double> =
        records.groupBy { it.time.toLocalDate() }.mapValues { (_, v) -> v.sumOf { it.mm } }
}
