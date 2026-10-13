package ke.geoai.agroweather.domain

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

object Meteo {
    /** Stull (2011) wet-bulb approximation, valid for RH 5–99 % and T −20…50 °C. */
    fun wetBulbC(tempC: Double, rhPct: Double): Double {
        val rh = rhPct.coerceIn(1.0, 100.0)
        return tempC * atan(0.151977 * sqrt(rh + 8.313659)) +
            atan(tempC + rh) - atan(rh - 1.676331) +
            0.00391838 * rh.pow(1.5) * atan(0.023101 * rh) - 4.686035
    }

    /** Delta-T (dry bulb − wet bulb): the standard spray-suitability index. 2–8 °C is ideal. */
    fun deltaT(tempC: Double, rhPct: Double): Double = tempC - wetBulbC(tempC, rhPct)

    private val DIRS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** Compass label for the direction the wind blows FROM. */
    fun compass(deg: Double): String = DIRS[((((deg % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16]
}
