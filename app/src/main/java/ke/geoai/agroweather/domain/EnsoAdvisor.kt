package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.OniReading

enum class EnsoPhase(val label: String) { EL_NINO("El Niño"), LA_NINA("La Niña"), NEUTRAL("Neutral") }

enum class EnsoStrength(val label: String) {
    NONE("—"), WEAK("Weak"), MODERATE("Moderate"), STRONG("Strong"), VERY_STRONG("Very strong")
}

enum class EnsoTrend(val label: String) { RISING("Strengthening"), FALLING("Weakening"), STEADY("Steady") }

data class EnsoStatus(
    val latest: OniReading,
    val phase: EnsoPhase,
    val strength: EnsoStrength,
    val trend: EnsoTrend,
    val history: List<OniReading>,
)

data class EnsoImpact(val headline: String, val outlook: String, val actions: List<String>)

/**
 * ONI = 3-month running mean of Niño-3.4 SST anomaly. ±0.5 °C is the usual threshold;
 * NOAA's official "El Niño advisory" also requires atmospheric coupling, so status can differ.
 */
object EnsoAdvisor {
    fun status(readings: List<OniReading>): EnsoStatus? {
        if (readings.isEmpty()) return null
        val latest = readings.last()
        val a = latest.anomaly
        val phase = when {
            a >= 0.5 -> EnsoPhase.EL_NINO
            a <= -0.5 -> EnsoPhase.LA_NINA
            else -> EnsoPhase.NEUTRAL
        }
        val mag = kotlin.math.abs(a)
        val strength = when {
            phase == EnsoPhase.NEUTRAL -> EnsoStrength.NONE
            mag >= 2.0 -> EnsoStrength.VERY_STRONG
            mag >= 1.5 -> EnsoStrength.STRONG
            mag >= 1.0 -> EnsoStrength.MODERATE
            else -> EnsoStrength.WEAK
        }
        val prev = readings.getOrNull(readings.size - 4)?.anomaly
        val trend = when {
            prev == null -> EnsoTrend.STEADY
            a - prev >= 0.3 -> EnsoTrend.RISING
            a - prev <= -0.3 -> EnsoTrend.FALLING
            else -> EnsoTrend.STEADY
        }
        return EnsoStatus(latest, phase, strength, trend, readings.takeLast(12))
    }

    /**
     * General East-Africa teleconnection guidance: El Niño tends to enhance Oct–Dec short rains
     * over much of Kenya (strongest in the east and coast); La Niña tends to suppress them.
     * The link to the Mar–May long rains is weak. Local forecasts from KMD/ICPAC take precedence.
     */
    fun kenyaImpact(s: EnsoStatus, month: Int): EnsoImpact {
        val shortRains = month in 10..12 || month in 1..2
        return when (s.phase) {
            EnsoPhase.EL_NINO -> EnsoImpact(
                headline = "${s.strength.label} El Niño – wetter than normal short rains likely",
                outlook = if (shortRains)
                    "The Oct–Dec short rains are likely above normal across much of Kenya, strongest in the east and coast; wet spells can extend into Jan–Feb."
                else
                    "The strongest Kenyan signal arrives with the Oct–Dec short rains. Use this period to prepare drainage, seed and storage.",
                actions = listOf(
                    "Plant with the first reliable rains; avoid low-lying or poorly drained fields.",
                    "Open and clean drains, contour bunds and terraces before heavy rain; watch slope erosion.",
                    "Spray only in dry windows with rain-fast products; scout for blights, rusts and leaf spots.",
                    "Hold back irrigation; split nitrogen top-dressing to limit leaching.",
                    "Prepare drying and storage (tarps, driers) – humid harvests raise mould and aflatoxin risk.",
                    "Check the official KMD and ICPAC (GHACOF) seasonal forecasts for your county.",
                ),
            )
            EnsoPhase.LA_NINA -> EnsoImpact(
                headline = "${s.strength.label} La Niña – drier short rains likely",
                outlook = "The Oct–Dec short rains tend to be below normal, raising drought risk in marginal and eastern areas.",
                actions = listOf(
                    "Favour drought-tolerant, short-maturity varieties.",
                    "Mulch and conserve moisture; harvest and store rainwater.",
                    "Schedule irrigation tightly using the water-balance guidance.",
                    "Check the official KMD and ICPAC seasonal forecasts.",
                ),
            )
            EnsoPhase.NEUTRAL -> EnsoImpact(
                headline = "ENSO-neutral – no strong seasonal signal",
                outlook = "Rely on the local 7- and 14-day forecasts and the official seasonal outlook.",
                actions = listOf("Follow the daily decision cards; check KMD and ICPAC seasonal forecasts."),
            )
        }
    }
}
