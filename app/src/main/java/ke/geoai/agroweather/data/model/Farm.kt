package ke.geoai.agroweather.data.model

import java.time.LocalDate

/**
 * Crop parameters follow FAO-56 (Allen et al. 1998) single-Kc values and stage lengths,
 * with p = fraction of total available water that can be depleted before stress.
 * They are sensible defaults, not site calibration.
 */
enum class Crop(
    val label: String,
    val rootDepthMm: Double,
    val depletionP: Double,
    val kcIni: Double,
    val kcMid: Double,
    val kcEnd: Double,
    val lIni: Int,
    val lDev: Int,
    val lMid: Int,
    val lLate: Int,
    val perennial: Boolean = false,
    val minSoilTempC: Double = 10.0,
) {
    MAIZE("Maize", 900.0, 0.55, 0.30, 1.20, 0.35, 20, 35, 40, 30, minSoilTempC = 12.0),
    BEANS("Beans", 600.0, 0.45, 0.40, 1.15, 0.35, 20, 30, 40, 20, minSoilTempC = 15.0),
    TOMATO("Tomato", 700.0, 0.40, 0.60, 1.15, 0.80, 30, 40, 40, 25, minSoilTempC = 15.0),
    POTATO("Potato", 500.0, 0.35, 0.50, 1.15, 0.75, 25, 30, 45, 30, minSoilTempC = 8.0),
    WHEAT("Wheat", 900.0, 0.55, 0.40, 1.15, 0.25, 20, 25, 60, 30, minSoilTempC = 5.0),
    SORGHUM("Sorghum", 1000.0, 0.55, 0.30, 1.05, 0.55, 20, 35, 40, 30, minSoilTempC = 15.0),
    KALE("Kale / cabbage", 500.0, 0.45, 0.70, 1.05, 0.95, 40, 60, 50, 15, minSoilTempC = 8.0),
    COFFEE("Coffee", 1000.0, 0.40, 0.95, 0.95, 0.95, 0, 0, 0, 0, perennial = true, minSoilTempC = 0.0),
    TEA("Tea", 1000.0, 0.40, 1.00, 1.00, 1.00, 0, 0, 0, 0, perennial = true, minSoilTempC = 0.0),
    OTHER("Other / mixed", 600.0, 0.50, 0.50, 1.05, 0.70, 25, 35, 40, 25, minSoilTempC = 10.0);

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)

    /** Crop coefficient [dap] days after planting. null = planting date unknown. */
    fun kc(dap: Int?): Double {
        if (perennial) return kcMid
        if (dap == null) return kcMid * 0.85
        if (dap < 0) return 0.30                       // not yet planted: bare-soil evaporation
        val devEnd = lIni + lDev
        val midEnd = devEnd + lMid
        return when {
            dap < lIni -> kcIni
            dap < devEnd -> lerp(kcIni, kcMid, (dap - lIni).toDouble() / lDev)
            dap < midEnd -> kcMid
            dap < midEnd + lLate -> lerp(kcMid, kcEnd, (dap - midEnd).toDouble() / lLate)
            else -> kcEnd
        }
    }

    /** Effective rooting depth grows from 35 % to 100 % of maximum by end of development stage. */
    fun rootDepthAt(dap: Int?): Double {
        if (perennial || dap == null) return rootDepthMm
        if (dap < 0) return rootDepthMm * 0.35
        val frac = (dap.toDouble() / (lIni + lDev).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return rootDepthMm * (0.35 + 0.65 * frac)
    }

    val totalDays: Int get() = lIni + lDev + lMid + lLate
}

/** Typical volumetric field capacity / wilting point (m³/m³) by soil class. */
enum class SoilType(val label: String, val fieldCapacity: Double, val wiltingPoint: Double) {
    SANDY("Sandy", 0.18, 0.07),
    LOAM("Loam", 0.30, 0.13),
    CLAY("Clay", 0.42, 0.26),
    NITISOL("Red volcanic clay (nitisol)", 0.40, 0.24),
    VERTISOL("Black cotton (vertisol)", 0.45, 0.28),
}

data class FarmProfile(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val crop: Crop,
    val soil: SoilType,
    val plantingDate: LocalDate?,
    /** Latest hand-measured volumetric water content (fraction, e.g. 0.27) and when it was logged. */
    val probeVwc: Double?,
    val probeAtMs: Long?,
    val alertsEnabled: Boolean = true,
)
