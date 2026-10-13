package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.HourPoint
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hour-by-hour spray suitability using the usual label/extension rules of thumb:
 * wind 3–15 km/h, gusts < 30, no rain for ~3 h (rain-fast period), T < 28–32 °C, Delta-T 2–8, daylight.
 * Product labels always take precedence.
 */
object SprayAdvisor {
    data class Assessment(val time: LocalDateTime, val light: Light, val reasons: List<String>)

    data class Window(val start: LocalDateTime, val endExclusive: LocalDateTime) {
        val hours: Int get() = Duration.between(start, endExclusive).toHours().toInt()
    }

    private val dayHm = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun assess(hours: List<HourPoint>, daily: List<DayPoint>): List<Assessment> =
        hours.mapIndexed { i, h ->
            val lookahead = hours.subList(i, minOf(hours.size, i + 3)) // this hour + next two
            assessHour(h, lookahead, isDaylight(h.time, daily))
        }

    fun isDaylight(t: LocalDateTime, daily: List<DayPoint>): Boolean {
        val day = daily.firstOrNull { it.date == t.toLocalDate() }
        val rise = day?.sunrise?.hour ?: 6
        val set = day?.sunset?.hour ?: 18
        return t.hour in rise until set
    }

    fun assessHour(h: HourPoint, lookahead: List<HourPoint>, daylight: Boolean): Assessment {
        val red = mutableListOf<String>()
        val amber = mutableListOf<String>()

        if (!daylight) red += "Night – inversion and drift risk, poor visibility"

        val wind = h.windKmh.roundToInt()
        when {
            h.windKmh > 20 -> red += "Wind $wind km/h – high drift risk"
            h.windKmh > 15 -> amber += "Breezy ($wind km/h) – use coarse droplets"
            h.windKmh < 3 -> amber += "Near-calm ($wind km/h) – inversion risk, fine droplets can hang"
        }
        if (h.gustKmh > 30) red += "Gusts to ${h.gustKmh.roundToInt()} km/h"

        val rain = lookahead.sumOf { it.precipMm }
        val prob = lookahead.maxOf { it.precipProbPct ?: 0 }
        when {
            rain >= 0.3 -> red += "Rain within 3 h – wash-off risk"
            prob >= 60 -> red += "Rain chance $prob % – wash-off risk"
            prob >= 35 -> amber += "Rain chance $prob %"
        }

        when {
            h.tempC > 32 -> red += "Hot (${h.tempC.roundToInt()} °C) – rapid evaporation/volatilisation"
            h.tempC > 28 -> amber += "Warm (${h.tempC.roundToInt()} °C)"
        }
        if (h.humidityPct < 40) amber += "Dry air (RH ${h.humidityPct.roundToInt()} %)"

        val dt = Meteo.deltaT(h.tempC, h.humidityPct)
        val dtTxt = String.format(Locale.US, "%.1f", dt)
        when {
            dt > 10 -> red += "Delta-T $dtTxt – droplets evaporate before landing"
            dt > 8 -> amber += "Delta-T $dtTxt – marginal evaporation"
            dt < 2 -> amber += "Delta-T $dtTxt – slow drying, run-off risk"
        }

        val light = when {
            red.isNotEmpty() -> Light.RED
            amber.isNotEmpty() -> Light.AMBER
            else -> Light.GREEN
        }
        return Assessment(h.time, light, red + amber)
    }

    /** Contiguous GREEN runs of at least [minHours]. */
    fun windows(a: List<Assessment>, minHours: Int = 2): List<Window> {
        val out = mutableListOf<Window>()
        var start = -1
        for (i in 0..a.size) {
            val good = i < a.size && a[i].light == Light.GREEN
            if (good && start < 0) start = i
            if (!good && start >= 0) {
                if (i - start >= minHours) out += Window(a[start].time, a[i - 1].time.plusHours(1))
                start = -1
            }
        }
        return out
    }

    /** [a] must start at the current hour. */
    fun advise(a: List<Assessment>): Advice {
        if (a.isEmpty()) return Advice(Light.AMBER, "NO DATA", "Hourly data unavailable")
        val first = a.first()
        val ws = windows(a)
        val extra = "${ws.size} good window${if (ws.size == 1) "" else "s"} in the next ${a.size.coerceAtMost(48)} h"

        return when {
            first.light == Light.GREEN -> {
                val run = ws.firstOrNull { !it.start.isAfter(first.time) && it.endExclusive.isAfter(first.time) }
                val until = run?.endExclusive ?: first.time.plusHours(1)
                Advice(Light.GREEN, "GO", "Good to spray now – until ${hm.format(until)}", listOf(extra, LABEL_NOTE))
            }
            ws.isNotEmpty() -> {
                val w = ws.first()
                Advice(
                    Light.AMBER, "WAIT",
                    "Next good window: ${dayHm.format(w.start)}–${hm.format(w.endExclusive)} (${w.hours} h)",
                    first.reasons.take(3).map { "Now: $it" } + extra + LABEL_NOTE,
                )
            }
            else -> Advice(
                Light.RED, "HOLD",
                "No safe spray window in the next ${a.size.coerceAtMost(48)} h",
                first.reasons.take(3).map { "Now: $it" } + LABEL_NOTE,
            )
        }
    }

    private const val LABEL_NOTE = "Always follow the product label for wind, temperature and rain-fast limits."
}
