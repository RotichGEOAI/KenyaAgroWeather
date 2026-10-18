package ke.geoai.agroweather.data.parse

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.ModelHour
import ke.geoai.agroweather.data.model.ModelSeries
import ke.geoai.agroweather.data.model.WeatherModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime

/**
 * Parses a single-model response. The column for a variable is looked up as `<var>_<modelId>` first and as plain
 * `<var>` second, because Open-Meteo may or may not suffix names when only one model is requested.
 */
object ModelParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.col(name: String, id: String): List<Double?> {
        val arr = (this["${name}_$id"] ?: this[name]) as? JsonArray ?: return emptyList()
        return arr.map { (it as? JsonPrimitive)?.doubleOrNull }
    }

    fun parse(model: WeatherModel, body: String): ModelSeries {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw ApiException("Unreadable response from weather service")
        }
        if ((root["error"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw ApiException((root["reason"] as? JsonPrimitive)?.contentOrNull ?: "Weather service error")
        }
        val h = root["hourly"] as? JsonObject ?: throw ApiException("No hourly data for ${model.label}")
        val times = (h["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val rain = h.col("precipitation", model.id)
        val temp = h.col("temperature_2m", model.id)
        val wind = h.col("wind_speed_10m", model.id)
        val cloud = h.col("cloud_cover", model.id)
        val cape = h.col("cape", model.id)

        val hours = times.indices.mapNotNull { i ->
            val t = times[i]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val r = rain.getOrNull(i)
            val tc = temp.getOrNull(i)
            // an hour the model does not provide (all core values null) is dropped, never filled with zeros
            if (r == null && tc == null) return@mapNotNull null
            ModelHour(t, r, tc, wind.getOrNull(i), cloud.getOrNull(i), cape.getOrNull(i))
        }
        if (hours.isEmpty()) throw ApiException("${model.label} has no data for this location")
        return ModelSeries(model, hours)
    }
}
