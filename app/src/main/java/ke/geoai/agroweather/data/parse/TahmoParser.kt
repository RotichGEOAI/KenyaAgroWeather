package ke.geoai.agroweather.data.parse

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.TahmoRainRecord
import ke.geoai.agroweather.data.model.TahmoStation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object TahmoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw ApiException("Unreadable response from TAHMO")
    }

    private fun JsonObject.num(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull }

    /**
     * The stations list is `{"data":[{...,"code":"TA00016", ...}]}`. TAHMO's published examples only read `code`, so the
     * coordinates are looked for in the usual places (`location.latitude`/`longitude`, then top-level lat/lon).
     * Stations without usable coordinates are skipped, never given a made-up position.
     */
    fun parseStations(body: String): List<TahmoStation> {
        val data = root(body)["data"] as? JsonArray ?: throw ApiException("TAHMO returned no station list")
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val code = (o["code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val loc = o["location"] as? JsonObject
            val lat = loc?.num("latitude", "lat") ?: o.num("latitude", "lat")
            val lon = loc?.num("longitude", "lon", "lng") ?: o.num("longitude", "lon", "lng")
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            TahmoStation(code, (o["name"] as? JsonPrimitive)?.contentOrNull, lat, lon)
        }
    }

    /**
     * Measurements: `{"results":[{"series":[{"columns":[time,quality,variable,sensor,value,...],"values":[[...]]}]}]}`.
     * Only quality == 1 records are kept (as in TAHMO's own example). Times are UTC and are shifted to local.
     */
    fun parseRain(body: String, utcOffsetSeconds: Int): List<TahmoRainRecord> {
        val results = root(body)["results"] as? JsonArray ?: return emptyList()
        val out = ArrayList<TahmoRainRecord>()
        for (r in results) {
            val series = ((r as? JsonObject)?.get("series") as? JsonArray) ?: continue
            for (s in series) {
                val so = s as? JsonObject ?: continue
                val cols = (so["columns"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                val ti = cols.indexOf("time"); val qi = cols.indexOf("quality"); val vi = cols.indexOf("value")
                if (ti < 0 || vi < 0) continue
                for (row in (so["values"] as? JsonArray) ?: continue) {
                    val a = row as? JsonArray ?: continue
                    val time = (a.getOrNull(ti) as? JsonPrimitive)?.contentOrNull ?: continue
                    val v = (a.getOrNull(vi) as? JsonPrimitive)?.doubleOrNull ?: continue
                    if (qi >= 0 && (a.getOrNull(qi) as? JsonPrimitive)?.intOrNull != 1) continue
                    val inst = runCatching { OffsetDateTime.parse(time).toInstant() }.getOrNull()
                        ?: runCatching { Instant.parse(time) }.getOrNull() ?: continue
                    if (v < 0) continue
                    out += TahmoRainRecord(LocalDateTime.ofEpochSecond(inst.epochSecond, 0, ZoneOffset.ofTotalSeconds(utcOffsetSeconds)), v)
                }
            }
        }
        return out.sortedBy { it.time }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun nearest(stations: List<TahmoStation>, lat: Double, lon: Double): Pair<TahmoStation, Double>? =
        stations.map { it to distanceKm(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }
}
