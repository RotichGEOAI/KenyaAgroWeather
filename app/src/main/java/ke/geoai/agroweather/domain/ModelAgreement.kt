package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.ModelResult
import java.time.LocalDate
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
}
