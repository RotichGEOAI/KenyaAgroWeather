package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.FarmProfile
import ke.geoai.agroweather.data.model.Forecast
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class OutlookStats(
    val rain7Mm: Double,
    val rain14Mm: Double,
    val normal7Mm: Double?,
    val normal14Mm: Double?,
    val et0Sum7Mm: Double,
    val windyDays: Int,
    val sprayHoursByDate: Map<LocalDate, Int>,
    val recentRain48hMm: Double,
    val cloudMeanByDate: Map<LocalDate, Int>,
    val capeMaxByDate: Map<LocalDate, Double>,
)

data class Analysis(
    val now: LocalDateTime,
    val hours: List<ke.geoai.agroweather.data.model.HourPoint>,       // next 48 h
    val spray: List<SprayAdvisor.Assessment>,                          // next 48 h
    val sprayAdvice: Advice,
    val water: WaterBalance.Plan,
    val waterAdvice: Advice,
    val soil: SoilSnapshot,
    val soilAdvice: Advice,
    val fieldAdvice: Advice,
    val alerts: List<Alert>,
    val outlook: OutlookStats,
    val rain: List<RainRisk>,                                          // next 48 h, aligned with [hours]
    val rainAdvice: Advice,
)

/** One pure entry point shared by the dashboard and the background alert worker. */
object Analyzer {
    private val PROBE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(3)

    fun analyze(
        farm: FarmProfile,
        fc: Forecast,
        clim: Climatology?,
        now: LocalDateTime,
        nowMs: Long = System.currentTimeMillis(),
    ): Analysis {
        val today = now.toLocalDate()
        val allHours = fc.hoursFrom(now, 24 * 16)
        val hours48 = allHours.take(48)
        val days = fc.daysFrom(today, 16)

        val sprayAll = SprayAdvisor.assess(allHours, fc.daily)
        val spray48 = sprayAll.take(48)
        val sprayAdvice = SprayAdvisor.advise(spray48)

        val rainAll = RainRiskAdvisor.assess(allHours)
        val rain48 = rainAll.take(48)
        val rainAdvice = RainRiskAdvisor.advise(rain48, hours48)

        // Soil starting point: fresh probe reading > model layers > assumption
        val modelVwc = hours48.firstOrNull()?.soilMoisture?.rootZoneMean()
        val probeFresh = farm.probeVwc != null && farm.probeAtMs != null && nowMs - farm.probeAtMs <= PROBE_MAX_AGE_MS
        val (theta, source) = when {
            probeFresh -> farm.probeVwc!! to WaterBalance.Source.PROBE
            modelVwc != null -> modelVwc to WaterBalance.Source.MODEL
            else -> null to WaterBalance.Source.ASSUMED
        }
        val soil = SoilSnapshot(theta, hours48.firstOrNull()?.soilTempC, source)

        val rain24 = hours48.take(24).sumOf { it.precipMm }
        val plan = WaterBalance.plan(farm, days, theta, source, rain24, today)
        val waterAdvice = WaterBalance.advise(plan, farm, today)
        val fieldAdvice = FieldWorkAdvisor.advise(farm, soil, days, plan)
        val soilAdvice = soilAdvice(farm, soil, plan)

        val alerts = AlertEngine.evaluate(hours48, days).toMutableList()
        if (plan.firstTriggerIndex == 0 && !plan.holdForRain && !plan.saturatedNow && !plan.notPlantedYet) {
            alerts += Alert(
                "IRRIGATE:$today", Severity.WATCH, "Irrigation due today",
                "${farm.name}: about ${plan.recommendedMm?.roundToInt() ?: 0} mm needed for ${farm.crop.label.lowercase()}.",
            )
        }

        val storm = RainRiskAdvisor.stormHours(rain48.take(24), hours48.take(24))
        if (storm.isNotEmpty()) {
            alerts += Alert(
                "STORM_RISK:$today", Severity.WATCH, "Thunderstorm risk",
                "High storm energy with building cloud ${RainRiskAdvisor.stormWindow(storm)}. The model may under-show the rain: " +
                    "hold spraying and field work, and keep away from open fields and trees during lightning.",
            )
        }

        val d7 = days.take(7)
        val d14 = days.take(14)
        val normal = clim?.let { c -> { list: List<ke.geoai.agroweather.data.model.DayPoint> -> list.sumOf { c.dailyPrecip(it.date) } } }
        val sprayHours = sprayAll.groupBy { it.time.toLocalDate() }
            .mapValues { (_, v) -> v.count { it.light == Light.GREEN } }

        val outlook = OutlookStats(
            rain7Mm = d7.sumOf { it.precipMm },
            rain14Mm = d14.sumOf { it.precipMm },
            normal7Mm = normal?.invoke(d7),
            normal14Mm = normal?.invoke(d14),
            et0Sum7Mm = d7.sumOf { it.et0Mm ?: 0.0 },
            windyDays = d7.count { (it.gustMaxKmh ?: 0.0) >= 40 },
            sprayHoursByDate = sprayHours,
            recentRain48hMm = fc.recentRainMm(today),
            cloudMeanByDate = allHours.groupBy { it.time.toLocalDate() }
                .mapValues { (_, v) -> v.mapNotNull { it.cloudPct }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0 },
            capeMaxByDate = allHours.groupBy { it.time.toLocalDate() }
                .mapValues { (_, v) -> v.mapNotNull { it.capeJkg }.maxOrNull() ?: 0.0 },
        )

        return Analysis(
            now, hours48, spray48, sprayAdvice, plan, waterAdvice, soil, soilAdvice, fieldAdvice,
            alerts.sortedByDescending { it.severity.ordinal }, outlook, rain48, rainAdvice,
        )
    }

    private fun soilAdvice(farm: FarmProfile, soil: SoilSnapshot, plan: WaterBalance.Plan): Advice {
        val vwc = soil.vwc
        val pctAvail = if (plan.tawMm > 0) ((1 - plan.startDepletionMm / plan.tawMm) * 100).roundToInt().coerceIn(0, 100) else 0
        val srcNote = when (soil.source) {
            WaterBalance.Source.PROBE -> "From your probe reading"
            WaterBalance.Source.MODEL -> "Model estimate (0–27 cm) – not calibrated to your soil"
            WaterBalance.Source.ASSUMED -> "No soil data available"
        }
        val details = listOfNotNull(
            vwc?.let { "Volumetric water content ${(it * 100).roundToInt()} % (field capacity ${(farm.soil.fieldCapacity * 100).roundToInt()} %, wilting ${(farm.soil.wiltingPoint * 100).roundToInt()} %)" },
            soil.tempC?.let { "Soil temperature (6 cm) ${it.roundToInt()} °C" },
            srcNote,
        )
        val light = when {
            plan.saturatedNow -> Light.AMBER
            pctAvail <= (100 - farm.crop.depletionP * 100) -> Light.RED
            pctAvail <= (100 - farm.crop.depletionP * 100) + 15 -> Light.AMBER
            else -> Light.GREEN
        }
        val badge = when {
            plan.saturatedNow -> "WET"
            light == Light.RED -> "DRY"
            light == Light.AMBER -> "LOW"
            else -> "OK"
        }
        val headline = if (plan.saturatedNow) "Soil saturated" else "$pctAvail % of plant-available water left"
        return Advice(light, badge, headline, details)
    }
}
