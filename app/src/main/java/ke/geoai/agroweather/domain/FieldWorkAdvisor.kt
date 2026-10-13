package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.FarmProfile
import java.util.Locale
import kotlin.math.roundToInt

data class SoilSnapshot(
    val vwc: Double?,                 // m³/m³
    val tempC: Double?,               // 6 cm
    val source: WaterBalance.Source,
)

/** Tillage / planting / field-traffic guidance from near-term rain, soil wetness and soil temperature. */
object FieldWorkAdvisor {
    fun advise(
        farm: FarmProfile,
        soil: SoilSnapshot,
        days: List<DayPoint>,
        plan: WaterBalance.Plan?,
    ): Advice {
        val rain48 = days.take(2).sumOf { it.precipMm }
        val reasons = mutableListOf<String>()
        var light = Light.GREEN

        fun raise(to: Light) { if (to.ordinal > light.ordinal) light = to }

        when {
            rain48 >= 30 -> { raise(Light.RED); reasons += "Heavy rain (${rain48.roundToInt()} mm) forecast in 48 h" }
            rain48 >= 15 -> { raise(Light.AMBER); reasons += "Rain (${rain48.roundToInt()} mm) forecast in 48 h" }
        }
        if (plan?.saturatedNow == true) {
            raise(Light.AMBER); reasons += "Soil is at/above field capacity – compaction and smearing risk"
        }
        val st = soil.tempC
        if (st != null && st < farm.crop.minSoilTempC) {
            raise(Light.AMBER)
            reasons += "Soil ${String.format(Locale.US, "%.0f", st)} °C is below ${farm.crop.minSoilTempC.roundToInt()} °C – slow germination"
        }
        if (reasons.isEmpty() && st != null) {
            reasons += "Soil ${String.format(Locale.US, "%.0f", st)} °C, no heavy rain in the next 48 h"
        }

        return when (light) {
            Light.GREEN -> Advice(Light.GREEN, "GO", "Good for tillage, planting and field traffic", reasons)
            Light.AMBER -> Advice(Light.AMBER, "CAUTION", "Marginal – work only well-drained fields", reasons)
            Light.RED -> Advice(Light.RED, "DELAY", "Delay field work – soil will be too wet", reasons)
        }
    }
}
