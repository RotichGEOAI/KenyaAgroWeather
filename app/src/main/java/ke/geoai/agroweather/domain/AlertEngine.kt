package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.HourPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class Severity { WATCH, WARNING }

/** [id] embeds the type and date so notifications are de-duplicated per event. */
data class Alert(val id: String, val severity: Severity, val title: String, val message: String)

object AlertEngine {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    private fun label(d: LocalDate) = dayFmt.format(d)

    /** [hours] from the current hour; [days] from today. */
    fun evaluate(hours: List<HourPoint>, days: List<DayPoint>): List<Alert> {
        val out = mutableListOf<Alert>()
        val next3 = days.take(3)

        // Intense hourly rainfall in the next 24 h
        val peak = hours.take(24).maxByOrNull { it.precipMm }
        if (peak != null && peak.precipMm >= 15) {
            out += Alert(
                "DOWNPOUR:${peak.time.toLocalDate()}", Severity.WARNING, "Intense downpour expected",
                "Up to ${peak.precipMm.roundToInt()} mm in one hour around ${peak.time.hour}:00 – flash-flood and erosion risk.",
            )
        }

        // Heavy daily rain
        next3.filter { it.precipMm >= 25 }.maxByOrNull { it.precipMm }?.let { d ->
            val sev = if (d.precipMm >= 50) Severity.WARNING else Severity.WATCH
            out += Alert(
                "HEAVY_RAIN:${d.date}", sev, if (sev == Severity.WARNING) "Very heavy rain" else "Heavy rain",
                "${d.precipMm.roundToInt()} mm forecast on ${label(d.date)}. Clear drains, delay spraying and field work.",
            )
        }

        // Multi-day accumulation → flood / waterlogging
        val sum3 = next3.sumOf { it.precipMm }
        if (sum3 >= 60) {
            val sev = if (sum3 >= 100) Severity.WARNING else Severity.WATCH
            out += Alert(
                "FLOOD_RISK:${next3.first().date}", sev, "Flood / waterlogging risk",
                "${sum3.roundToInt()} mm expected over 3 days. Low-lying fields and river banks are at risk.",
            )
        }

        // Wind
        next3.filter { (it.gustMaxKmh ?: 0.0) >= 40 }.maxByOrNull { it.gustMaxKmh ?: 0.0 }?.let { d ->
            val g = d.gustMaxKmh ?: 0.0
            out += Alert(
                "WIND:${d.date}", if (g >= 60) Severity.WARNING else Severity.WATCH, "Strong winds",
                "Gusts up to ${g.roundToInt()} km/h on ${label(d.date)} – risk of lodging and structure damage; no spraying.",
            )
        }

        // Heat
        next3.filter { it.tMaxC >= 35 }.maxByOrNull { it.tMaxC }?.let { d ->
            out += Alert(
                "HEAT:${d.date}", Severity.WATCH, "Heat stress",
                "Maximum ${d.tMaxC.roundToInt()} °C on ${label(d.date)}. Water early and avoid midday spraying.",
            )
        }

        // Frost / cold (highland crops, tea, potato)
        next3.filter { it.tMinC <= 4 }.minByOrNull { it.tMinC }?.let { d ->
            out += Alert(
                "COLD:${d.date}", if (d.tMinC <= 2) Severity.WARNING else Severity.WATCH,
                if (d.tMinC <= 2) "Frost risk" else "Cold night",
                "Minimum ${String.format(Locale.US, "%.0f", d.tMinC)} °C on ${label(d.date)}.",
            )
        }

        // Dry spell
        val next7 = days.take(7)
        if (next7.size >= 7 && next7.sumOf { it.precipMm } < 5) {
            out += Alert(
                "DRY_SPELL:${next7.first().date}", Severity.WATCH, "Dry week ahead",
                "Less than 5 mm of rain forecast over 7 days. Plan irrigation and mulching.",
            )
        }

        return out.sortedByDescending { it.severity.ordinal }
    }
}
