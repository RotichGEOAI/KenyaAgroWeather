package ke.geoai.agroweather.data.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Raised for upstream API / parsing problems that are not worth retrying. */
class ApiException(message: String) : Exception(message)

/** Volumetric soil moisture (m³/m³) from the model, by layer depth in cm. */
data class SoilMoistureLayers(
    val s0to1: Double?,
    val s1to3: Double?,
    val s3to9: Double?,
    val s9to27: Double?,
) {
    /** Thickness-weighted mean over 0–27 cm, ignoring missing layers. */
    fun rootZoneMean(): Double? {
        val parts = listOf(s0to1 to 1.0, s1to3 to 2.0, s3to9 to 6.0, s9to27 to 18.0)
            .filter { it.first != null }
        if (parts.isEmpty()) return null
        val w = parts.sumOf { it.second }
        return parts.sumOf { it.first!! * it.second } / w
    }
}

data class HourPoint(
    val time: LocalDateTime,            // local time at the farm
    val tempC: Double,
    val humidityPct: Double,
    val dewPointC: Double?,
    val precipMm: Double,
    val precipProbPct: Int?,
    val windKmh: Double,                // 10 m
    val gustKmh: Double,
    val windDirDeg: Double,             // direction the wind blows FROM
    val cloudPct: Double?,
    val et0Mm: Double?,
    val soilMoisture: SoilMoistureLayers,
    val soilTempC: Double?,             // 6 cm
)

data class DayPoint(
    val date: LocalDate,
    val tMaxC: Double,
    val tMinC: Double,
    val precipMm: Double,
    val precipProbMaxPct: Int?,
    val windMaxKmh: Double?,
    val gustMaxKmh: Double?,
    val et0Mm: Double?,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
)

data class Forecast(
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double?,
    val utcOffsetSeconds: Int,
    val hourly: List<HourPoint>,
    val daily: List<DayPoint>,
    val fetchedAtMs: Long,
) {
    fun localNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    /** Hourly points from the current hour onward. */
    fun hoursFrom(now: LocalDateTime, count: Int): List<HourPoint> {
        val start = now.truncatedTo(ChronoUnit.HOURS)
        return hourly.filter { !it.time.isBefore(start) }.take(count)
    }

    fun daysFrom(today: LocalDate, count: Int): List<DayPoint> =
        daily.filter { !it.date.isBefore(today) }.take(count)

    /** Rain that fell in the 48 h before [today] (needs past_days in the request). */
    fun recentRainMm(today: LocalDate): Double =
        daily.filter { it.date.isBefore(today) && !it.date.isBefore(today.minusDays(2)) }.sumOf { it.precipMm }
}

data class SeasonalDay(
    val date: LocalDate,
    val tMaxC: Double?,
    val tMinC: Double?,
    val precipMm: Double?,
)

/** 12-element monthly climatology (index 0 = January). */
class Climatology(
    val tempC: DoubleArray,
    val precipMonthMm: DoubleArray,
    val label: String,
) {
    fun temp(month: Int): Double = tempC[month - 1]
    fun dailyPrecip(date: LocalDate): Double = precipMonthMm[date.monthValue - 1] / date.lengthOfMonth()
}

data class MonthOutlook(
    val month: YearMonth,
    val tempMeanC: Double,
    val precipMm: Double,               // estimated full-month total
    val tempAnomalyC: Double?,
    val precipAnomalyPct: Double?,
    val coveredDays: Int,
    val partial: Boolean,
)

data class OniReading(
    val season: String,                 // e.g. "JAS"
    val year: Int,
    val total: Double,
    val anomaly: Double,
)

data class GeoPlace(
    val name: String,
    val admin1: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double?,
) {
    val label: String get() = listOfNotNull(name, admin1, country).joinToString(", ")
}

data class RadarFrame(val timeEpochSec: Long, val path: String, val nowcast: Boolean)

data class RadarFrames(val host: String, val frames: List<RadarFrame>)
