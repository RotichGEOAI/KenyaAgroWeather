package ke.geoai.agroweather.data.parse

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.Forecast
import ke.geoai.agroweather.data.model.GeoPlace
import ke.geoai.agroweather.data.model.HourPoint
import ke.geoai.agroweather.data.model.SeasonalDay
import ke.geoai.agroweather.data.model.SoilMoistureLayers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

object OpenMeteoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.doubles(key: String): List<Double?> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()

    private fun JsonObject.strings(key: String): List<String?> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun parseRoot(body: String): JsonObject {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw ApiException("Unreadable response from weather service")
        }
        if ((root["error"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw ApiException((root["reason"] as? JsonPrimitive)?.contentOrNull ?: "Weather service error")
        }
        return root
    }

    /**
     * An hour/day is kept only if its core variables are present. Optional variables
     * (probability, soil, ET0, cloud) stay null rather than being invented.
     */
    fun parseForecast(body: String, fetchedAtMs: Long): Forecast {
        val root = parseRoot(body)
        val h = root["hourly"] as? JsonObject ?: throw ApiException("Forecast has no hourly data")
        val d = root["daily"] as? JsonObject ?: throw ApiException("Forecast has no daily data")

        val times = h.strings("time")
        val temp = h.doubles("temperature_2m")
        val rh = h.doubles("relative_humidity_2m")
        val dew = h.doubles("dew_point_2m")
        val pr = h.doubles("precipitation")
        val pp = h.doubles("precipitation_probability")
        val ws = h.doubles("wind_speed_10m")
        val wg = h.doubles("wind_gusts_10m")
        val wd = h.doubles("wind_direction_10m")
        val cc = h.doubles("cloud_cover")
        val et = h.doubles("et0_fao_evapotranspiration")
        val s01 = h.doubles("soil_moisture_0_to_1cm")
        val s13 = h.doubles("soil_moisture_1_to_3cm")
        val s39 = h.doubles("soil_moisture_3_to_9cm")
        val s927 = h.doubles("soil_moisture_9_to_27cm")
        val st = h.doubles("soil_temperature_6cm")

        val hours = ArrayList<HourPoint>(times.size)
        for (i in times.indices) {
            val t = times[i]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: continue
            val tc = temp.getOrNull(i) ?: continue
            val hum = rh.getOrNull(i) ?: continue
            val rain = pr.getOrNull(i) ?: continue
            val wind = ws.getOrNull(i) ?: continue
            hours += HourPoint(
                time = t,
                tempC = tc,
                humidityPct = hum,
                dewPointC = dew.getOrNull(i),
                precipMm = rain,
                precipProbPct = pp.getOrNull(i)?.roundToInt(),
                windKmh = wind,
                gustKmh = wg.getOrNull(i) ?: wind,
                windDirDeg = wd.getOrNull(i) ?: 0.0,
                cloudPct = cc.getOrNull(i),
                et0Mm = et.getOrNull(i),
                soilMoisture = SoilMoistureLayers(s01.getOrNull(i), s13.getOrNull(i), s39.getOrNull(i), s927.getOrNull(i)),
                soilTempC = st.getOrNull(i),
            )
        }
        if (hours.isEmpty()) throw ApiException("Forecast contained no usable hours")

        val dTimes = d.strings("time")
        val tMax = d.doubles("temperature_2m_max")
        val tMin = d.doubles("temperature_2m_min")
        val dPr = d.doubles("precipitation_sum")
        val dPp = d.doubles("precipitation_probability_max")
        val dWs = d.doubles("wind_speed_10m_max")
        val dWg = d.doubles("wind_gusts_10m_max")
        val dEt = d.doubles("et0_fao_evapotranspiration")
        val rise = d.strings("sunrise")
        val set = d.strings("sunset")

        val days = ArrayList<DayPoint>(dTimes.size)
        for (i in dTimes.indices) {
            val date = dTimes[i]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val mx = tMax.getOrNull(i) ?: continue
            val mn = tMin.getOrNull(i) ?: continue
            val rain = dPr.getOrNull(i) ?: continue
            days += DayPoint(
                date = date,
                tMaxC = mx,
                tMinC = mn,
                precipMm = rain,
                precipProbMaxPct = dPp.getOrNull(i)?.roundToInt(),
                windMaxKmh = dWs.getOrNull(i),
                gustMaxKmh = dWg.getOrNull(i),
                et0Mm = dEt.getOrNull(i),
                sunrise = rise.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                sunset = set.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            )
        }
        if (days.isEmpty()) throw ApiException("Forecast contained no usable days")

        return Forecast(
            latitude = (root["latitude"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            longitude = (root["longitude"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            elevationM = (root["elevation"] as? JsonPrimitive)?.doubleOrNull,
            utcOffsetSeconds = (root["utc_offset_seconds"] as? JsonPrimitive)?.intOrNull ?: 0,
            hourly = hours,
            daily = days,
            fetchedAtMs = fetchedAtMs,
        )
    }

    /**
     * The seasonal endpoint is an ensemble. If a plain variable key exists it is used as-is;
     * otherwise any `<name>_memberNN` columns are averaged per day.
     */
    fun parseSeasonal(body: String): List<SeasonalDay> {
        val root = parseRoot(body)
        val d = root["daily"] as? JsonObject ?: throw ApiException("Seasonal forecast has no daily data")
        val times = d.strings("time")

        fun column(base: String): List<Double?> {
            val cols: List<List<Double?>> =
                if (d.containsKey(base)) listOf(d.doubles(base))
                else d.keys.filter { it.startsWith(base + "_member") }.map { d.doubles(it) }
            return times.indices.map { i ->
                cols.mapNotNull { it.getOrNull(i) }.takeIf { it.isNotEmpty() }?.average()
            }
        }

        val tMax = column("temperature_2m_max")
        val tMin = column("temperature_2m_min")
        val pr = column("precipitation_sum")
        val out = times.indices.mapNotNull { i ->
            val date = times[i]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
            SeasonalDay(date, tMax[i], tMin[i], pr[i])
        }
        if (out.none { it.precipMm != null || it.tMaxC != null }) throw ApiException("Seasonal forecast was empty")
        return out
    }

    /** Builds a 12-month climatology from ERA5-based daily archive data. */
    fun parseClimatology(body: String, label: String): Climatology {
        val root = parseRoot(body)
        val d = root["daily"] as? JsonObject ?: throw ApiException("Climatology has no daily data")
        val times = d.strings("time")
        val tm = d.doubles("temperature_2m_mean")
        val pr = d.doubles("precipitation_sum")

        val tSum = DoubleArray(12)
        val tN = IntArray(12)
        val pTotals = HashMap<Pair<Int, Int>, Double>()
        val pDays = HashMap<Pair<Int, Int>, Int>()

        for (i in times.indices) {
            val date = times[i]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val m = date.monthValue - 1
            tm.getOrNull(i)?.let { tSum[m] += it; tN[m]++ }
            pr.getOrNull(i)?.let {
                val key = date.year to m
                pTotals[key] = (pTotals[key] ?: 0.0) + it
                pDays[key] = (pDays[key] ?: 0) + 1
            }
        }
        val temp = DoubleArray(12) { if (tN[it] == 0) Double.NaN else tSum[it] / tN[it] }
        val precip = DoubleArray(12) { m ->
            val monthTotals = pTotals.filter { it.key.second == m && (pDays[it.key] ?: 0) >= 25 }.values
            if (monthTotals.isEmpty()) Double.NaN else monthTotals.average()
        }
        if (temp.any { it.isNaN() } || precip.any { it.isNaN() }) throw ApiException("Climatology incomplete")
        return Climatology(temp, precip, label)
    }

    fun climatologyToJson(c: Climatology): String = buildJsonObject {
        put("label", c.label)
        putJsonArray("t") { c.tempC.forEach { add(it) } }
        putJsonArray("p") { c.precipMonthMm.forEach { add(it) } }
    }.toString()

    fun climatologyFromJson(body: String): Climatology {
        val o = json.parseToJsonElement(body).jsonObject
        fun arr(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
        return Climatology(arr("t"), arr("p"), o["label"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    fun parseGeocoding(body: String): List<GeoPlace> {
        val root = parseRoot(body)
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            GeoPlace(
                name = name,
                admin1 = (o["admin1"] as? JsonPrimitive)?.contentOrNull,
                country = (o["country"] as? JsonPrimitive)?.contentOrNull,
                latitude = lat,
                longitude = lon,
                elevationM = (o["elevation"] as? JsonPrimitive)?.doubleOrNull,
            )
        }
    }
}
