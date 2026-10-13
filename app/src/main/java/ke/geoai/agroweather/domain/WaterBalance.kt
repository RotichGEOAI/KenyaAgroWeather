package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.FarmProfile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * FAO-56 style root-zone water balance, run forward over the daily forecast:
 *   Dr(next) = Dr − effective rain + ETc,   ETc = Kc × ET0
 * Irrigation is triggered when depletion Dr reaches readily available water (RAW = p × TAW).
 */
object WaterBalance {
    enum class Source { PROBE, MODEL, ASSUMED }

    data class Step(
        val date: LocalDate,
        val et0Mm: Double,
        val kc: Double,
        val etcMm: Double,
        val rainMm: Double,
        val effRainMm: Double,
        val depletionStartMm: Double,
        val irrigationMm: Double,
        val depletionEndMm: Double,
    )

    data class Plan(
        val tawMm: Double,
        val rawMm: Double,
        val rootDepthMm: Double,
        val startDepletionMm: Double,
        val steps: List<Step>,
        val firstTriggerIndex: Int?,
        val recommendedMm: Double?,
        val holdForRain: Boolean,
        val saturatedNow: Boolean,
        val notPlantedYet: Boolean,
        val source: Source,
    )

    private const val MAX_APPLICATION_MM = 40.0
    private const val FALLBACK_ET0 = 4.0
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Rain below ~2 mm is intercepted/evaporated; the rest is ~85 % effective. */
    fun effectiveRain(mm: Double): Double = if (mm <= 2.0) 0.0 else (mm - 2.0) * 0.85

    fun plan(
        farm: FarmProfile,
        days: List<DayPoint>,
        theta0: Double?,
        source: Source,
        rainNext24hMm: Double,
        today: LocalDate,
        horizonDays: Int = 14,
    ): Plan {
        val soil = farm.soil
        val crop = farm.crop
        val dap0 = farm.plantingDate?.let { ChronoUnit.DAYS.between(it, today).toInt() }
        val zr = crop.rootDepthAt(dap0)
        val taw = (soil.fieldCapacity - soil.wiltingPoint) * zr
        val raw = crop.depletionP * taw

        val theta = theta0 ?: (soil.wiltingPoint + 0.6 * (soil.fieldCapacity - soil.wiltingPoint))
        val saturated = theta >= soil.fieldCapacity
        var dr = ((soil.fieldCapacity - theta) * zr).coerceIn(0.0, taw)
        val startDr = dr

        val knownEt0 = days.mapNotNull { it.et0Mm }
        val et0Fallback = if (knownEt0.isNotEmpty()) knownEt0.average() else FALLBACK_ET0

        val steps = ArrayList<Step>()
        var firstTrigger: Int? = null
        var recommended: Double? = null

        days.take(horizonDays).forEachIndexed { i, d ->
            val dap = farm.plantingDate?.let { ChronoUnit.DAYS.between(it, d.date).toInt() }
            val planted = dap == null || dap >= 0
            val kc = crop.kc(dap)
            val et0 = d.et0Mm ?: et0Fallback
            val etc = et0 * kc
            val eff = effectiveRain(d.precipMm)

            val startOfDay = dr
            var irrigation = 0.0
            if (planted && dr >= raw) {
                irrigation = minOf(dr, MAX_APPLICATION_MM)
                dr -= irrigation
                if (firstTrigger == null) {
                    firstTrigger = i
                    recommended = irrigation
                }
            }
            dr = (dr - eff + etc).coerceIn(0.0, taw)
            steps += Step(d.date, et0, kc, etc, d.precipMm, eff, startOfDay, irrigation, dr)
        }

        return Plan(
            tawMm = taw,
            rawMm = raw,
            rootDepthMm = zr,
            startDepletionMm = startDr,
            steps = steps,
            firstTriggerIndex = firstTrigger,
            recommendedMm = recommended,
            holdForRain = firstTrigger == 0 && rainNext24hMm >= 8.0,
            saturatedNow = saturated,
            notPlantedYet = dap0 != null && dap0 < 0,
            source = source,
        )
    }

    fun advise(plan: Plan, farm: FarmProfile, today: LocalDate): Advice {
        val pctDepleted = if (plan.tawMm > 0) (plan.startDepletionMm / plan.tawMm * 100).roundToInt() else 0
        val first = plan.steps.firstOrNull()
        val details = buildList {
            add("Root-zone depletion $pctDepleted % of available water (irrigate at ${(farm.crop.depletionP * 100).roundToInt()} %)")
            add("Root zone ${plan.rootDepthMm.roundToInt()} mm · ${farm.soil.label} · ${farm.crop.label}")
            if (first != null) add("Today's crop water use ≈ ${fmt(first.etcMm)} mm (ET0 ${fmt(first.et0Mm)} × Kc ${fmt(first.kc)})")
            add(
                when (plan.source) {
                    Source.PROBE -> "Starting moisture from your recent probe reading"
                    Source.MODEL -> "Starting moisture from the weather-model soil layers – log a probe reading for field accuracy"
                    Source.ASSUMED -> "No soil-moisture data – starting point assumed; log a probe reading"
                }
            )
            if (farm.plantingDate == null) add("Set a planting date for an accurate crop coefficient")
            add("1 mm of water = 10 m³ per hectare")
        }

        val idx = plan.firstTriggerIndex
        val mm = plan.recommendedMm
        return when {
            plan.notPlantedYet -> Advice(Light.GREEN, "OK", "Not planted yet – irrigation scheduling starts at planting", details)
            plan.saturatedNow -> Advice(Light.AMBER, "WET", "Soil at/above field capacity – skip irrigation, check drainage", details)
            idx == 0 && plan.holdForRain ->
                Advice(Light.AMBER, "WAIT", "Water is due but rain is forecast within 24 h – hold off and recheck", details)
            idx == 0 && mm != null ->
                Advice(Light.RED, "IRRIGATE", "Irrigate today: about ${mm.roundToInt()} mm (${(mm * 10).roundToInt()} m³/ha)", details)
            idx != null && idx <= 2 && mm != null ->
                Advice(Light.AMBER, "SOON", "Irrigation due ${dayFmt.format(plan.steps[idx].date)}: about ${mm.roundToInt()} mm", details)
            idx != null && mm != null ->
                Advice(Light.GREEN, "OK", "Moisture adequate – next irrigation ≈ ${dayFmt.format(plan.steps[idx].date)} (${mm.roundToInt()} mm)", details)
            else -> Advice(Light.GREEN, "OK", "No irrigation needed in the next ${plan.steps.size} days", details)
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
