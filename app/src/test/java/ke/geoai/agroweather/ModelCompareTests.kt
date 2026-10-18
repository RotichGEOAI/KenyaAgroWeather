package ke.geoai.agroweather

import ke.geoai.agroweather.data.CacheRecord
import ke.geoai.agroweather.data.CacheStore
import ke.geoai.agroweather.data.WeatherRepository
import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.ModelHour
import ke.geoai.agroweather.data.model.ModelResult
import ke.geoai.agroweather.data.model.ModelSeries
import ke.geoai.agroweather.data.model.WeatherModel
import ke.geoai.agroweather.data.net.HttpFetcher
import ke.geoai.agroweather.data.parse.ModelParser
import ke.geoai.agroweather.domain.ModelAgreement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ModelParserTest {
    private val m = WeatherModel("dwd_icon_seamless", "ICON", "DWD")

    private fun body(suffix: String, rain: String = "[0.0,1.5,null]") =
        """{"hourly":{"time":["2026-10-18T00:00","2026-10-18T01:00","2026-10-18T02:00"],
        "precipitation$suffix":$rain,"temperature_2m$suffix":[18.0,17.5,null],
        "wind_speed_10m$suffix":[5,6,7],"cloud_cover$suffix":[10,20,30],"cape$suffix":[0,5,null]}}"""

    @Test fun suffixedColumnsAreRead() {
        val s = ModelParser.parse(m, body("_dwd_icon_seamless"))
        assertEquals(2, s.hours.size)           // third hour has no rain and no temp -> dropped
        assertEquals(1.5, s.hours[1].precipMm!!, 1e-9)
        assertEquals(6.0, s.hours[1].windKmh!!, 1e-9)
    }

    @Test fun plainColumnsAreReadToo() {
        val s = ModelParser.parse(m, body(""))
        assertEquals(2, s.hours.size)
        assertEquals(17.5, s.hours[1].tempC!!, 1e-9)
    }

    @Test fun otherModelsColumnsAreNotMistaken() {
        val other = body("_ecmwf_ifs")
        // asking for ICON when only ECMWF columns exist must not silently use them
        try { ModelParser.parse(m, other); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("no data")) }
    }

    @Test fun errorBodyIsReported() {
        try { ModelParser.parse(m, """{"error":true,"reason":"Cannot initialize model"}"""); fail() }
        catch (e: ApiException) { assertEquals("Cannot initialize model", e.message) }
    }

    @Test fun garbageIsRejected() {
        try { ModelParser.parse(m, "<html>"); fail() } catch (e: ApiException) { }
    }
}

class ModelAgreementTest {
    private val d0 = LocalDate.of(2026, 10, 18)

    private fun result(label: String, vararg dayRain: Double): ModelResult {
        val hours = dayRain.flatMapIndexed { di, mm ->
            (0 until 24).map { h ->
                ModelHour(d0.plusDays(di.toLong()).atTime(h, 0), mm / 24.0, 20.0, 10.0, 50.0, 0.0)
            }
        }
        val model = WeatherModel(label.lowercase(), label, "x")
        return ModelResult(model, ModelSeries(model, hours), null)
    }

    @Test fun allDryAgree() {
        val days = ModelAgreement.daily(listOf(result("A", 0.0), result("B", 0.2), result("C", 0.0)), d0, 1)
        assertEquals(ModelAgreement.Verdict.DRY, days[0].verdict)
        assertEquals(0, days[0].wetCount)
    }

    @Test fun disagreementIsFlaggedAndSpreadReported() {
        val days = ModelAgreement.daily(listOf(result("A", 0.0), result("B", 12.0), result("C", 6.0)), d0, 1)
        val d = days[0]
        assertEquals(ModelAgreement.Verdict.SPLIT, d.verdict)
        assertEquals(2, d.wetCount)
        assertEquals(12.0, ModelAgreement.spreadMm(d), 1e-6)
        assertEquals(6.0, d.meanMm!!, 1e-6)
    }

    @Test fun allWet() {
        val d = ModelAgreement.daily(listOf(result("A", 5.0), result("B", 8.0)), d0, 1)[0]
        assertEquals(ModelAgreement.Verdict.WET, d.verdict)
    }

    @Test fun failedModelDoesNotCountAndSingleModelIsThin() {
        val failed = ModelResult(WeatherModel("z", "Z", "x"), null, "down")
        val d = ModelAgreement.daily(listOf(result("A", 9.0), failed), d0, 1)[0]
        assertEquals(ModelAgreement.Verdict.THIN, d.verdict)
        assertEquals(1, d.reporting)
    }

    @Test fun partialDayIsNotCompared() {
        val short = result("S", 30.0).let { r ->
            ModelResult(r.model, ModelSeries(r.model, r.series!!.hours.take(6)), null)   // only 6 hours of day 0
        }
        val d = ModelAgreement.daily(listOf(short, result("A", 0.0), result("B", 0.0)), d0, 1)[0]
        assertNull(d.perModel[0].rainMm)
        assertEquals(2, d.reporting)
        assertEquals(ModelAgreement.Verdict.DRY, d.verdict)
    }

    @Test fun headlineNamesFirstRain() {
        val days = ModelAgreement.daily(listOf(result("A", 0.0, 10.0), result("B", 0.0, 0.0)), d0, 2)
        assertTrue(ModelAgreement.headline(days).contains("2026-10-19"))
    }
}

class ModelRepositoryTest {
    private class Mem : CacheStore {
        val map = HashMap<String, CacheRecord>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(record: CacheRecord) { map[record.key] = record }
    }

    /** Serves a valid body for every model except the one whose id is rejected, like a service refusing an unknown id. */
    private class Http(val bad: String) : HttpFetcher {
        var calls = 0
        override suspend fun get(url: String): String {
            calls++
            if (url.contains("models=$bad")) throw ApiException("Invalid model: $bad")
            return """{"hourly":{"time":["2026-10-18T00:00"],"precipitation":[1.0],"temperature_2m":[20.0]}}"""
        }
    }

    private val metBody = """{"properties":{"timeseries":[
        {"time":"2026-10-18T00:00:00Z","data":{"instant":{"details":{"air_temperature":18.0,"wind_speed":2.0,"cloud_area_fraction":40.0}},
          "next_1_hours":{"details":{"precipitation_amount":0.5}}}}]}}"""
    private val pirateBody = """{"hourly":{"data":[{"time":1792281600,"precipIntensity":0.4,"temperature":19.0,"windSpeed":7.0,"cloudCover":0.3}]}}"""

    private class Multi(val bad: String, val met: String, val pirate: String) : HttpFetcher {
        var calls = 0
        val urls = ArrayList<String>()
        override suspend fun get(url: String): String {
            calls++; urls += url
            return when {
                url.contains("models=$bad") -> throw ApiException("Invalid model: $bad")
                url.contains("api.met.no") -> met
                url.contains("pirateweather") -> pirate
                else -> """{"hourly":{"time":["2026-10-18T00:00"],"precipitation":[1.0],"temperature_2m":[20.0]}}"""
            }
        }
    }

    @Test fun oneBadModelDoesNotSinkTheOthers() = runBlocking {
        val http = Multi("ukmo_seamless", metBody, pirateBody)
        val r = WeatherModel.DEFAULTS.let { WeatherRepository(http, Mem()) { 1L }.compareModels(-1.28, 36.82, pirateKey = "k") }
        assertEquals(6, r.size)
        assertEquals(5, r.count { it.series != null })
        val bad = r.first { it.series == null }
        assertEquals("ukmo_seamless", bad.model.id)
        assertNotNull(bad.error)
    }

    @Test fun pirateIsSkippedWithoutKeyAndNeverRequested() = runBlocking {
        val http = Multi("none", metBody, pirateBody)
        val r = WeatherRepository(http, Mem()) { 1L }.compareModels(-1.28, 36.82)
        val p = r.first { it.model.id == "pirate_weather" }
        assertNull(p.series)
        assertTrue(http.urls.none { it.contains("pirateweather") })
    }

    @Test fun keyedFailureDoesNotEchoKey() = runBlocking {
        val http = object : HttpFetcher {
            override suspend fun get(url: String): String = throw ApiException("boom " + url)
        }
        val r = WeatherRepository(http, Mem()) { 1L }.compareModels(-1.28, 36.82, pirateKey = "SECRETKEY")
        assertTrue(r.none { it.error?.contains("SECRETKEY") == true })
    }

    @Test fun resultsAreCachedPerModel() = runBlocking {
        val http = Multi("none", metBody, pirateBody)
        val repo = WeatherRepository(http, Mem()) { 1L }
        repo.compareModels(-1.28, 36.82, pirateKey = "k"); repo.compareModels(-1.28, 36.82, pirateKey = "k")
        assertEquals(6, http.calls)
    }
}


class ExtraParserTest {
    private val met = WeatherModel("met_norway", "MET Norway", "x", ke.geoai.agroweather.data.model.ModelSource.MET_NORWAY)
    private val pirate = WeatherModel("pirate_weather", "Pirate", "x", ke.geoai.agroweather.data.model.ModelSource.PIRATE_WEATHER)

    @Test fun metNorwayUnitsTimeAndSixHourlySpread() {
        val body = """{"properties":{"timeseries":[
          {"time":"2026-10-18T00:00:00Z","data":{"instant":{"details":{"air_temperature":18.0,"wind_speed":2.0,"cloud_area_fraction":40.0}},
            "next_1_hours":{"details":{"precipitation_amount":0.5}}}},
          {"time":"2026-10-20T00:00:00Z","data":{"instant":{"details":{"air_temperature":20.0,"wind_speed":5.0,"cloud_area_fraction":90.0}},
            "next_6_hours":{"details":{"precipitation_amount":6.0}}}},
          {"time":"2026-10-20T06:00:00Z","data":{"instant":{"details":{"air_temperature":21.0}}}}]}}"""
        val s = ke.geoai.agroweather.data.parse.MetNoParser.parse(met, body, 3 * 3600)
        assertEquals(1 + 6, s.hours.size)                              // last step has no precipitation -> dropped
        assertEquals(LocalDateTime.of(2026, 10, 18, 3, 0), s.hours[0].time)   // UTC -> +3 h
        assertEquals(7.2, s.hours[0].windKmh!!, 1e-9)                  // 2 m/s
        assertEquals(1.0, s.hours[3].precipMm!!, 1e-9)                 // 6 mm / 6 h
    }

    @Test fun pirateUnitsAndCloudFraction() {
        val body = """{"hourly":{"data":[{"time":1792281600,"precipIntensity":0.4,"temperature":19.0,"windSpeed":7.0,"cloudCover":0.3}]}}"""
        val s = ke.geoai.agroweather.data.parse.PirateParser.parse(pirate, body, 0)
        assertEquals(30.0, s.hours[0].cloudPct!!, 1e-9)
        assertEquals(0.4, s.hours[0].precipMm!!, 1e-9)
    }

    @Test fun pirateErrorMessageSurfaces() {
        try { ke.geoai.agroweather.data.parse.PirateParser.parse(pirate, """{"message":"Invalid key"}""", 0); fail() }
        catch (e: ApiException) { assertEquals("Invalid key", e.message) }
    }

    @Test fun wmsTileBboxIsLonLatAndCoversKenya() {
        // z=0 is the whole world
        val w = ke.geoai.agroweather.data.parse.Wms.tileBbox(0, 0, 0)
        assertEquals(-180.0, w[0], 1e-9); assertEquals(180.0, w[2], 1e-9); assertEquals(85.0511, w[3], 1e-3)
        // z=7 tile containing Nairobi (36.82E, 1.29S): x=77..., y=64...
        val b = ke.geoai.agroweather.data.parse.Wms.tileBbox(7, 82, 64)
        assertTrue(b[0] < b[2] && b[1] < b[3])
        assertTrue(ke.geoai.agroweather.data.parse.ApiUrls.eumetsatTile("msg_iodc:ir108", 7, 82, 64).contains("srs=EPSG:4326"))
    }

    @Test fun hourlySeriesGapsAndCumulative() {
        val from = LocalDateTime.of(2026, 10, 18, 0, 0)
        val hrs = listOf(
            ModelHour(from, 1.0, 20.0, 5.0, 10.0, null),
            ModelHour(from.plusHours(1), 2.0, 21.0, 6.0, 20.0, null),
            ModelHour(from.plusHours(3), 4.0, 22.0, 7.0, 30.0, null),
        )
        val cum = ModelAgreement.hourlySeries(hrs, ModelAgreement.Metric.RAIN_CUM, from, 4)
        assertEquals(listOf(1.0, 3.0, null, 7.0), cum)
        assertEquals(21.0, ModelAgreement.hourlySeries(hrs, ModelAgreement.Metric.TEMP, from, 4)[1]!!, 1e-9)
    }
}

class TahmoTest {
    private val stationsBody = """{"data":[
      {"code":"TA00016","location":{"latitude":-0.30,"longitude":36.10}},
      {"code":"TA00134","location":{"latitude":0.52,"longitude":35.27}},
      {"code":"TA00252","latitude":1.0,"longitude":34.0},
      {"code":"NOLOC"}]}"""
    private val measBody = """{"results":[{"series":[{"columns":["time","quality","variable","sensor","value"],"values":[
      ["2026-10-17T20:00:00.000Z",1,"pr","s1",0.5],
      ["2026-10-17T21:30:00Z",1,"pr","s1",1.5],
      ["2026-10-18T05:00:00Z",5,"pr","s1",9.0],
      ["2026-10-18T06:00:00Z",1,"pr","s1",2.0]]}]}]}"""

    @Test fun stationsParseAndSkipUnlocatedOnes() {
        val s = ke.geoai.agroweather.data.parse.TahmoParser.parseStations(stationsBody)
        assertEquals(listOf("TA00016", "TA00134", "TA00252"), s.map { it.code })
    }

    @Test fun nearestStationAndDistance() {
        val s = ke.geoai.agroweather.data.parse.TahmoParser.parseStations(stationsBody)
        val (st, km) = ke.geoai.agroweather.data.parse.TahmoParser.nearest(s, 0.5, 35.3)!!
        assertEquals("TA00134", st.code)
        assertTrue(km < 5.0)
    }

    @Test fun rainKeepsOnlyQualityOneAndShiftsToLocalTime() {
        val r = ke.geoai.agroweather.data.parse.TahmoParser.parseRain(measBody, 3 * 3600)
        assertEquals(3, r.size)                                   // quality 5 dropped
        assertEquals(LocalDateTime.of(2026, 10, 17, 23, 0), r[0].time)
        val tr = ke.geoai.agroweather.data.model.TahmoRain(
            ke.geoai.agroweather.data.model.TahmoStation("T", null, 0.0, 0.0), 1.0, r)
        assertEquals(0.5, tr.mmFor(LocalDate.of(2026, 10, 17)), 1e-9)    // 21:30Z is already 00:30 on the 18th locally
        assertEquals(3.5, tr.mmFor(LocalDate.of(2026, 10, 18)), 1e-9)    // 1.5 + 2.0
    }

    @Test fun verifyUsesOnlyWholeHoursAndSkipsPartialModels() {
        val d = LocalDate.of(2026, 10, 18)
        fun res(label: String, hours: Int, mmPerHour: Double): ModelResult {
            val m = WeatherModel(label, label, "x")
            return ModelResult(m, ModelSeries(m, (0 until hours).map { ModelHour(d.atTime(it, 0), mmPerHour, 20.0, 5.0, 10.0, null) }), null)
        }
        val checks = ModelAgreement.verify(listOf(res("A", 24, 0.5), res("B", 10, 5.0)), d, 9.0, untilHour = 12)
        assertEquals(1, checks.size)                       // B has only 10 of the 12 hours
        assertEquals(6.0, checks[0].forecastMm, 1e-9)      // 12 h x 0.5
        assertEquals(-3.0, checks[0].errorMm, 1e-9)
    }

    @Test fun credentialsGoToBasicAuthNotUrlOrCacheKeyOrError() = runBlocking {
        val seen = ArrayList<String>()
        val http = object : HttpFetcher {
            override suspend fun get(url: String): String = throw AssertionError("unauthenticated call")
            override suspend fun getBasic(url: String, user: String, password: String): String {
                seen += url
                assertEquals("KEYNAME", user); assertEquals("SECRETVALUE", password)
                return if (url.contains("/stations?")) stationsBody else measBody
            }
        }
        val cache = object : CacheStore {
            val map = HashMap<String, CacheRecord>()
            override suspend fun get(key: String) = map[key]
            override suspend fun put(record: CacheRecord) { map[record.key] = record }
        }
        val repo = WeatherRepository(http, cache) { 1_792_300_000_000L }
        val out = repo.tahmoRain(0.5, 35.3, "KEYNAME", "SECRETVALUE", 3 * 3600)
        assertEquals("TA00134", out.data!!.station.code)
        assertTrue(seen.none { it.contains("KEYNAME") || it.contains("SECRETVALUE") })
        assertTrue(cache.map.keys.none { it.contains("KEYNAME") || it.contains("SECRETVALUE") })
        assertTrue(cache.map.values.none { it.json.contains("SECRETVALUE") })
    }
}
