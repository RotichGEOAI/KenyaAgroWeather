package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.HourPoint
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** How the weather model's rain signal compares with what the cloud layers and storm energy imply. */
enum class RainAgreement { CLEAR, CONFIRMED, MODEL_DRY_CLOUD_WET, MODEL_WET_CLOUD_CLEAR, MIXED }

data class RainRisk(
    val time: LocalDateTime,
    val cloudSupportPct: Int,      // how much layered low/mid cloud is present (0-100)
    val convectivePct: Int,        // storm energy (CAPE) scaled 0-100, weighted by time of day
    val skyChancePct: Int,         // rain chance implied by cloud + humidity + storm energy
    val modelChancePct: Int,       // the model's own precipitation probability / amount signal
    val cautiousChancePct: Int,    // max(model, 0.8 x sky): can only add caution, never lowers the model
    val agreement: RainAgreement,
)

/**
 * Cloud component for rain. The weather model's precipitation field often misses small-scale convective showers
 * (typical of Kenya's afternoon storms). Cloud layers and CAPE are separate model outputs that tend to show the
 * setup even when the rain field is dry.
 *
 * This is a physically motivated cross-check, NOT a calibrated forecast: the weights below are not fitted to
 * Kenyan gauge data. It therefore only ever raises caution; it never lowers the model's own rain chance and never
 * changes the forecast millimetres.
 */
object RainRiskAdvisor {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** 0-1: depth of layered low/mid cloud (high cloud only counts when the lower sky is already cloudy). */
    fun cloudSupport(h: HourPoint): Double {
        val low = h.cloudLowPct
        val mid = h.cloudMidPct
        val v = if (low != null && mid != null) {
            val layered = (0.6 * low + 0.4 * mid) / 100.0
            val anvil = if (layered > 0.5 && h.cloudHighPct != null) 0.1 * h.cloudHighPct / 100.0 else 0.0
            layered + anvil
        } else {
            (h.cloudPct ?: 0.0) * 0.7 / 100.0   // total cloud only: discount, it may be thin cirrus
        }
        return v.coerceIn(0.0, 1.0)
    }

    /** 0-1: relative humidity from 60 % (no support) to 95 % (saturated). */
    fun moisture(h: HourPoint): Double = ((h.humidityPct - 60.0) / 35.0).coerceIn(0.0, 1.0)

    private fun diurnal(hour: Int): Double = when (hour) {
        in 11..19 -> 1.0          // peak convective heating
        in 8..10, in 20..22 -> 0.8
        else -> 0.6               // night: reduced, but lake-basin night storms exist
    }

    /** 0-1: CAPE from 200 J/kg (negligible) to 2000 J/kg (very unstable), weighted by time of day. */
    fun convective(h: HourPoint): Double {
        val cape = h.capeJkg ?: return 0.0
        return ((cape - 200.0) / 1800.0).coerceIn(0.0, 1.0) * diurnal(h.time.hour)
    }

    fun assess(h: HourPoint): RainRisk {
        val cs = cloudSupport(h)
        val base = cs * (0.4 + 0.6 * moisture(h))
        val conv = convective(h)
        val sky = (0.55 * base + 0.45 * conv * (0.5 + 0.5 * cs)).coerceIn(0.0, 1.0)
        val skyPct = (sky * 100).roundToInt()

        val fromAmount = when {
            h.precipMm >= 1.0 -> 70
            h.precipMm >= 0.3 -> 50
            else -> 0
        }
        val modelPct = max(h.precipProbPct ?: 0, fromAmount)
        val cautious = max(modelPct, (0.8 * skyPct).roundToInt())

        val modelWet = modelPct >= 50 || h.precipMm >= 0.3
        val modelDry = modelPct < 35 && h.precipMm < 0.1
        val skyWet = skyPct >= 55
        val skyDry = skyPct < 25
        val agreement = when {
            modelWet && skyWet -> RainAgreement.CONFIRMED
            modelDry && skyWet -> RainAgreement.MODEL_DRY_CLOUD_WET
            modelWet && skyDry -> RainAgreement.MODEL_WET_CLOUD_CLEAR
            modelDry && skyDry -> RainAgreement.CLEAR
            else -> RainAgreement.MIXED
        }
        return RainRisk(
            h.time, (cs * 100).roundToInt(), (conv * 100).roundToInt(), skyPct, modelPct, cautious, agreement,
        )
    }

    fun assess(hours: List<HourPoint>): List<RainRisk> = hours.map { assess(it) }

    fun energyLabel(capeJkg: Double?): String = when {
        capeJkg == null -> "n/a"
        capeJkg < 300 -> "Low"
        capeJkg < 1000 -> "Moderate"
        capeJkg < 2500 -> "High"
        else -> "Extreme"
    }

    private fun window(r: List<RainRisk>): String =
        if (r.size == 1) "around ${hm.format(r.first().time)}"
        else "between ${hm.format(r.first().time)} and ${hm.format(r.last().time)}"

    /** Decision card for the next 24 h. [risks] and [hours] must be aligned and start at the current hour. */
    fun advise(risks: List<RainRisk>, hours: List<HourPoint>): Advice {
        val r = risks.take(24)
        if (r.isEmpty()) return Advice(Light.AMBER, "NO DATA", "Cloud data unavailable")
        val h24 = hours.take(24)

        val peak = r.maxByOrNull { it.cautiousChancePct }!!
        val missed = r.filter { it.agreement == RainAgreement.MODEL_DRY_CLOUD_WET }
        val over = r.filter { it.agreement == RainAgreement.MODEL_WET_CLOUD_CLEAR }
        val peakModel = r.maxOf { it.modelChancePct }
        val peakSky = r.maxOf { it.skyChancePct }
        val peakCape = h24.mapNotNull { it.capeJkg }.maxOrNull()
        val now = h24.firstOrNull()

        val details = buildList {
            if (now != null) {
                val layers = if (now.cloudLowPct != null && now.cloudMidPct != null && now.cloudHighPct != null)
                    " (low ${now.cloudLowPct.roundToInt()} · mid ${now.cloudMidPct.roundToInt()} · high ${now.cloudHighPct.roundToInt()})" else ""
                add("Cloud now: ${(now.cloudPct ?: 0.0).roundToInt()} %$layers")
            }
            add("Next 24 h rain chance – model peak $peakModel % · cloud/storm-energy peak $peakSky %")
            if (peakCape != null) add("Storm energy (CAPE) peak ${peakCape.roundToInt()} J/kg – ${energyLabel(peakCape)}")
            if (missed.isNotEmpty()) add("${missed.size} h where cloud and instability favour rain that the model's rain field does not show")
            if (over.isNotEmpty()) add("${over.size} h where the model shows rain but the sky looks unsupportive – it may be over-forecast")
            add("A cross-check, not a calibrated forecast: it can only add caution and never reduces the model's rain chance.")
        }

        return when {
            missed.size >= 2 -> Advice(
                if (peak.cautiousChancePct >= 60) Light.RED else Light.AMBER, "CLOUD ALERT",
                "Model shows little rain, but cloud and storm energy favour showers ${window(missed)}", details,
            )
            peak.cautiousChancePct >= 60 -> Advice(
                Light.RED, "RAIN LIKELY", "Rain likely around ${hm.format(peak.time)} (chance up to ${peak.cautiousChancePct} %)", details,
            )
            peak.cautiousChancePct >= 35 -> Advice(
                Light.AMBER, "POSSIBLE", "Showers possible around ${hm.format(peak.time)} (chance up to ${peak.cautiousChancePct} %)", details,
            )
            else -> Advice(Light.GREEN, "DRY", "Little chance of rain in the next 24 h (up to ${peak.cautiousChancePct} %)", details)
        }
    }

    /** Hours in [hours]/[risks] (aligned) with high storm energy and cloud to release it. */
    fun stormHours(risks: List<RainRisk>, hours: List<HourPoint>): List<RainRisk> =
        risks.zip(hours).filter { (r, h) -> (h.capeJkg ?: 0.0) >= 1500.0 && r.skyChancePct >= 55 }.map { it.first }

    fun stormWindow(storm: List<RainRisk>): String = window(storm)
}
