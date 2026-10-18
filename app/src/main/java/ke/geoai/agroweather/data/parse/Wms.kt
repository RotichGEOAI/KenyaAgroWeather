package ke.geoai.agroweather.data.parse

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

object Wms {
    /**
     * WMS 1.1.1 bbox (minLon, minLat, maxLon, maxLat) in degrees for an XYZ web-mercator tile.
     * Asking an EPSG:4326 service for this box and drawing it in a mercator tile is a small approximation:
     * the error is negligible near the equator (Kenya) at the zooms used here.
     */
    fun tileBbox(z: Int, x: Int, y: Int): DoubleArray {
        val n = 2.0.pow(z)
        fun lon(xx: Int) = xx / n * 360.0 - 180.0
        fun lat(yy: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * yy / n))))
        return doubleArrayOf(lon(x), lat(y + 1), lon(x + 1), lat(y))
    }
}
