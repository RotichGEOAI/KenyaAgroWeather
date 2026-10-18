package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.ModelResult
import ke.geoai.agroweather.data.model.ModelHour
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

/** Side-by-side daily comparison across models, plus a plain-language agreement verdict. */
object ModelAgreement {
    const val WET_DAY_MM = 1.0

    enum class Verdict(val label: String) {
        DRY("All models dry"), WET("All models wet"), SPLIT("Models disagree"), THIN("Too few models"),
    }

    data class DayModel(val modelLabel: String, val rainMm: Double?, val tMaxC: Double?, val windMaxKmh: Double?)

    data class Day(
        val date: LocalDate,
        val perModel: List<DayModel>,
        val wetCount: Int,
        val reporting: Int,
        val minMm: Double?,
        val maxMm: Double?,
        val meanMm: Double?,
        val verdict: Verdict,
    )

    /**
     * A model counts for a day only if it covers (nearly) the whole day: a partial day would understate its rain.
     * Days start from today so past hours are not compared.
     */
    fun daily(results: List<ModelResult>, from: LocalDate, days: Int): List<Day> {
        val ok = results.filter { it.series != null }
        return (0 until days).map { off ->
            val date = from.plusDays(off.toLong())
            val per = results.map { r ->
                val hrs = r.series?.hours?.filter { it.time.toLocalDate() == date }.orEmpty()
                val full = hrs.size >= 20 && hrs.count { it.precipMm != null } >= 20
                DayModel(
                    r.model.label,
                    if (full) hrs.sumOf { it.precipMm ?: 0.0 } else null,
                    if (hrs.isNotEmpty()) hrs.mapNotNull { it.tempC }.maxOrNull() else null,
                    if (hrs.isNotEmpty()) hrs.mapNotNull { it.windKmh }.maxOrNull() else null,
                )
            }
            val rain = per.mapNotNull { it.rainMm }
            val wet = rain.count { it >= WET_DAY_MM }
            val verdict = when {
                rain.size < 2 || ok.size < 2 -> Verdict.THIN
                wet == 0 -> Verdict.DRY
                wet == rain.size -> Verdict.WET
                else -> Verdict.SPLIT
            }
            Day(
                date, per, wet, rain.size, rain.minOrNull(), rain.maxOrNull(),
                if (rain.isEmpty()) null else rain.average(), verdict,
            )
        }
    }

    /** One sentence for the headline: the wettest-day disagreement is what a farmer should plan around. */
    fun headline(days: List<Day>): String {
        val usable = days.filter { it.verdict != Verdict.THIN }
        if (usable.isEmpty()) return "Not enough models answered to compare."
        val split = usable.count { it.verdict == Verdict.SPLIT }
        val wet = usable.count { it.verdict == Verdict.WET }
        val firstWet = usable.firstOrNull { it.wetCount > 0 }
        return when {
            wet == 0 && split == 0 -> "All models keep the next ${usable.size} days dry."
            firstWet == null -> "No model shows rain."
            else -> "First rain signal: ${firstWet.date} (${firstWet.wetCount} of ${firstWet.reporting} models). " +
                "$split of ${usable.size} days have models disagreeing - treat those as uncertain."
        }
    }

    fun spreadMm(d: Day): Double = max((d.maxMm ?: 0.0) - (d.minMm ?: 0.0), 0.0)

    enum class Metric(val label: String, val unit: String) {
        RAIN_CUM("Cumulative rain", "mm"), TEMP("Temperature", "°C"), WIND("Wind", "km/h"), CLOUD("Cloud cover", "%"),
    }

    /**
     * One value per hour starting at [from] for chart lines. Hours a model does not cover are null (a gap, not zero).
     * Cumulative rain stops adding at the first missing hour's value but keeps the running total, so a short series
     * ends early instead of dropping back to 0.
     */
    fun hourlySeries(hours: List<ModelHour>, metric: Metric, from: LocalDateTime, count: Int): List<Double?> {
        val byTime = hours.associateBy { it.time }
        var running = 0.0
        return (0 until count).map { i ->
            val h = byTime[from.plusHours(i.toLong())]
            when (metric) {
                Metric.RAIN_CUM -> if (h?.precipMm == null) null else { running += h.precipMm; running }
                Metric.TEMP -> h?.tempC
                Metric.WIND -> h?.windKmh
                Metric.CLOUD -> h?.cloudPct
            }
        }
    }

    /** One model's total for a day against what a ground station recorded. */
    data class Check(val modelLabel: String, val forecastMm: Double, val observedMm: Double) {
        val errorMm: Double get() = forecastMm - observedMm
    }

    /**
     * Compares each model's rain for [date] with [observedMm]. A model with a partial day is skipped (for today, pass
     * [untilHour]: only hours before it are summed on both sides, so a half-day forecast is not judged against a full day).
     */
    fun verify(results: List<ModelResult>, date: LocalDate, observedMm: Double, untilHour: Int = 24): List<Check> =
        results.mapNotNull { r ->
            val hrs = r.series?.hours?.filter { it.time.toLocalDate() == date && it.time.hour < untilHour }.orEmpty()
            if (hrs.size < untilHour || hrs.count { it.precipMm != null } < untilHour) return@mapNotNull null
            Check(r.model.label, hrs.sumOf { it.precipMm ?: 0.0 }, observedMm)
        }
}
