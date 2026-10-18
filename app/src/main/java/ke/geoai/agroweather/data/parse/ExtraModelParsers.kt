package ke.geoai.agroweather.data.parse

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.ModelHour
import ke.geoai.agroweather.data.model.ModelSeries
import ke.geoai.agroweather.data.model.WeatherModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun root(body: String): JsonObject = try {
    lenient.parseToJsonElement(body).jsonObject
} catch (e: Exception) {
    throw ApiException("Unreadable response from weather service")
}

private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.arr(k: String) = this[k] as? JsonArray
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

/** MET Norway Locationforecast 2.0 compact JSON. Times are UTC; they are shifted to the farm's local clock. */
object MetNoParser {
    fun parse(model: WeatherModel, body: String, utcOffsetSeconds: Int): ModelSeries {
        val ts = root(body).obj("properties")?.arr("timeseries") ?: throw ApiException("No data from ${model.label}")
        val out = ArrayList<ModelHour>()
        for (e in ts) {
            val step = e as? JsonObject ?: continue
            val timeStr = (step["time"] as? JsonPrimitive)?.contentOrNull ?: continue
            val utc = runCatching { Instant.parse(timeStr) }.getOrNull() ?: continue
            val data = step.obj("data") ?: continue
            val inst = data.obj("instant")?.obj("details") ?: continue
            val temp = inst.num("air_temperature")
            // m/s -> km/h
            val wind = inst.num("wind_speed")?.times(3.6)
            val cloud = inst.num("cloud_area_fraction")
            val local = LocalDateTime.ofEpochSecond(utc.epochSecond, 0, ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
            val p1 = data.obj("next_1_hours")?.obj("details")?.num("precipitation_amount")
            if (p1 != null) {
                out += ModelHour(local, p1, temp, wind, cloud, null)
                continue
            }
            // beyond the hourly range the service gives 6-hourly steps: spread the amount evenly over those hours
            val p6 = data.obj("next_6_hours")?.obj("details")?.num("precipitation_amount")
            if (p6 != null) {
                for (k in 0 until 6) out += ModelHour(local.plusHours(k.toLong()), p6 / 6.0, temp, wind, cloud, null)
            }
        }
        if (out.isEmpty()) throw ApiException("${model.label} has no data for this location")
        return ModelSeries(model, out)
    }
}

/** Pirate Weather (Dark Sky compatible) with `units=ca`: degC, mm/h, km/h, cloudCover 0..1, time in epoch seconds. */
object PirateParser {
    fun parse(model: WeatherModel, body: String, utcOffsetSeconds: Int): ModelSeries {
        val r = root(body)
        val data = r.obj("hourly")?.arr("data")
            ?: throw ApiException((r["message"] as? JsonPrimitive)?.contentOrNull ?: "No data from ${model.label}")
        val out = data.mapNotNull { e ->
            val h = e as? JsonObject ?: return@mapNotNull null
            val t = (h["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val rain = h.num("precipIntensity")
            val temp = h.num("temperature")
            if (rain == null && temp == null) return@mapNotNull null
            ModelHour(
                LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.ofTotalSeconds(utcOffsetSeconds)),
                rain, temp, h.num("windSpeed"), h.num("cloudCover")?.times(100.0), null,
            )
        }
        if (out.isEmpty()) throw ApiException("${model.label} has no data for this location")
        return ModelSeries(model, out)
    }
}
