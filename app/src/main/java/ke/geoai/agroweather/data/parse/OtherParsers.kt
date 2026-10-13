package ke.geoai.agroweather.data.parse

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.OniReading
import ke.geoai.agroweather.data.model.RadarFrame
import ke.geoai.agroweather.data.model.RadarFrames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** NOAA CPC Oceanic Niño Index text file: "SEAS  YR  TOTAL  ANOM". */
object EnsoParser {
    fun parse(text: String): List<OniReading> {
        val out = text.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 4) return@mapNotNull null
            val year = p[1].toIntOrNull() ?: return@mapNotNull null
            val total = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val anom = p[3].toDoubleOrNull() ?: return@mapNotNull null
            if (p[0].length != 3) return@mapNotNull null
            OniReading(p[0], year, total, anom)
        }.toList()
        if (out.isEmpty()) throw ApiException("ENSO index file could not be read")
        return out
    }
}

/** RainViewer radar composite index. */
object RainViewerParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): RadarFrames {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw ApiException("Unreadable radar index")
        }
        val host = (root["host"] as? JsonPrimitive)?.contentOrNull ?: "https://tilecache.rainviewer.com"
        val radar = root["radar"] as? JsonObject ?: throw ApiException("Radar index has no frames")

        fun frames(key: String, nowcast: Boolean): List<RadarFrame> =
            (radar[key] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val t = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                RadarFrame(t, path, nowcast)
            } ?: emptyList()

        val all = frames("past", false) + frames("nowcast", true)
        if (all.isEmpty()) throw ApiException("No radar frames available")
        return RadarFrames(host, all.sortedBy { it.timeEpochSec })
    }
}
