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

    @Test fun oneBadModelDoesNotSinkTheOthers() = runBlocking {
        val http = Http("ukmo_seamless")
        val r = WeatherRepository(http, Mem()) { 1L }.compareModels(-1.28, 36.82)
        assertEquals(4, r.size)
        assertEquals(3, r.count { it.series != null })
        val bad = r.first { it.series == null }
        assertEquals("ukmo_seamless", bad.model.id)
        assertNotNull(bad.error)
    }

    @Test fun resultsAreCachedPerModel() = runBlocking {
        val http = Http("none")
        val repo = WeatherRepository(http, Mem()) { 1L }
        repo.compareModels(-1.28, 36.82); repo.compareModels(-1.28, 36.82)
        assertEquals(4, http.calls)
    }
}
