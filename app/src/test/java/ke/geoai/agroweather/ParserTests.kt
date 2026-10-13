package ke.geoai.agroweather

import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.SeasonalDay
import ke.geoai.agroweather.data.parse.EnsoParser
import ke.geoai.agroweather.data.parse.OpenMeteoParser
import ke.geoai.agroweather.data.parse.RainViewerParser
import ke.geoai.agroweather.domain.EnsoAdvisor
import ke.geoai.agroweather.domain.EnsoPhase
import ke.geoai.agroweather.domain.EnsoStrength
import ke.geoai.agroweather.domain.EnsoTrend
import ke.geoai.agroweather.domain.MonthlyOutlookBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class OpenMeteoParserTest {
    private val forecastJson = """
    {
      "latitude": 0.5, "longitude": 35.25, "elevation": 2100.0, "utc_offset_seconds": 10800,
      "hourly": {
        "time": ["2026-10-12T08:00","2026-10-12T09:00","2026-10-12T10:00","2026-10-12T11:00"],
        "temperature_2m": [18.2, 20.1, null, 23.0],
        "relative_humidity_2m": [80, 70, 65, 60],
        "dew_point_2m": [14.0, 14.5, 15.0, 15.0],
        "precipitation": [0.0, 0.2, 0.0, 1.4],
        "precipitation_probability": [10, 30, null, 70],
        "wind_speed_10m": [5.0, 7.5, 9.0, 12.0],
        "wind_gusts_10m": [9.0, 14.0, 18.0, null],
        "wind_direction_10m": [90, 100, 110, 120],
        "cloud_cover": [40, 50, 60, 70],
        "et0_fao_evapotranspiration": [0.1, 0.2, 0.3, 0.4],
        "soil_moisture_0_to_1cm": [0.30, 0.29, 0.28, null],
        "soil_moisture_1_to_3cm": [0.30, 0.29, 0.28, null],
        "soil_moisture_3_to_9cm": [0.31, 0.30, 0.29, null],
        "soil_moisture_9_to_27cm": [0.33, 0.33, 0.32, null],
        "soil_temperature_6cm": [17.0, 18.0, 19.0, 20.0]
      },
      "daily": {
        "time": ["2026-10-12"],
        "temperature_2m_max": [26.0], "temperature_2m_min": [12.0],
        "precipitation_sum": [4.2], "precipitation_probability_max": [60],
        "wind_speed_10m_max": [14.0], "wind_gusts_10m_max": [30.0],
        "et0_fao_evapotranspiration": [4.1],
        "sunrise": ["2026-10-12T06:20"], "sunset": ["2026-10-12T18:25"]
      }
    }""".trimIndent()

    @Test fun parsesForecastAndSkipsIncompleteHours() {
        val f = OpenMeteoParser.parseForecast(forecastJson, 1L)
        assertEquals(3, f.hourly.size)                      // hour with null temperature dropped
        assertEquals(10800, f.utcOffsetSeconds)
        assertEquals(2100.0, f.elevationM!!, 1e-9)
        val last = f.hourly.last()
        assertEquals(12.0, last.gustKmh, 1e-9)              // gust falls back to wind speed
        assertNull(last.soilMoisture.s0to1)
        assertEquals(1.4, last.precipMm, 1e-9)
        assertEquals(1, f.daily.size)
        assertEquals(6, f.daily[0].sunrise!!.hour)
    }

    @Test fun rootZoneMeanIsThicknessWeighted() {
        val f = OpenMeteoParser.parseForecast(forecastJson, 1L)
        val m = f.hourly.first().soilMoisture.rootZoneMean()!!
        assertTrue("mean=$m", m in 0.31..0.33)              // dominated by the 9–27 cm layer
    }

    @Test fun apiErrorIsReported() {
        try {
            OpenMeteoParser.parseForecast("""{"error":true,"reason":"Latitude must be in range"}""", 1L)
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("Latitude"))
        }
    }

    @Test fun garbageIsReported() {
        try { OpenMeteoParser.parseForecast("<html>", 1L); throw AssertionError("expected") } catch (e: ApiException) { }
    }

    @Test fun seasonalAveragesEnsembleMembers() {
        val body = """{"daily":{"time":["2026-11-01","2026-11-02"],
          "temperature_2m_max_member01":[26.0,27.0],"temperature_2m_max_member02":[28.0,null],
          "temperature_2m_min_member01":[14.0,15.0],
          "precipitation_sum_member01":[2.0,4.0],"precipitation_sum_member02":[4.0,6.0]}}"""
        val d = OpenMeteoParser.parseSeasonal(body)
        assertEquals(27.0, d[0].tMaxC!!, 1e-9)
        assertEquals(27.0, d[1].tMaxC!!, 1e-9)              // null member ignored
        assertEquals(3.0, d[0].precipMm!!, 1e-9)
        assertEquals(5.0, d[1].precipMm!!, 1e-9)
    }

    @Test fun seasonalPrefersPlainKey() {
        val body = """{"daily":{"time":["2026-11-01"],"temperature_2m_max":[30.0],"temperature_2m_min":[10.0],"precipitation_sum":[1.0]}}"""
        assertEquals(30.0, OpenMeteoParser.parseSeasonal(body)[0].tMaxC!!, 1e-9)
    }

    @Test fun climatologyAggregatesMonths() {
        val times = ArrayList<String>(); val t = ArrayList<String>(); val p = ArrayList<String>()
        var d = LocalDate.of(2020, 1, 1)
        while (d.year <= 2021) {
            times += "\"$d\""; t += (20.0 + d.monthValue).toString(); p += "1.0"; d = d.plusDays(1)
        }
        val body = """{"daily":{"time":[${times.joinToString(",")}],"temperature_2m_mean":[${t.joinToString(",")}],"precipitation_sum":[${p.joinToString(",")}]}}"""
        val c = OpenMeteoParser.parseClimatology(body, "test")
        assertEquals(21.0, c.temp(1), 1e-9)
        assertEquals(31.0, c.precipMonthMm[0], 1e-9)
        assertEquals(28.5, c.precipMonthMm[1], 1e-9)        // Feb: 29 (2020) and 28 (2021)
    }

    @Test fun climatologyJsonRoundTrip() {
        val c = Climatology(DoubleArray(12) { 20.0 + it }, DoubleArray(12) { 50.0 + it }, "x")
        val back = OpenMeteoParser.climatologyFromJson(OpenMeteoParser.climatologyToJson(c))
        assertEquals(c.tempC.toList(), back.tempC.toList())
        assertEquals(c.precipMonthMm.toList(), back.precipMonthMm.toList())
    }

    @Test fun geocodingParses() {
        val body = """{"results":[{"name":"Eldoret","latitude":0.52,"longitude":35.27,"elevation":2100.0,"country":"Kenya","admin1":"Uasin Gishu"},{"name":"bad"}]}"""
        val r = OpenMeteoParser.parseGeocoding(body)
        assertEquals(1, r.size)
        assertEquals("Eldoret, Uasin Gishu, Kenya", r[0].label)
        assertTrue(OpenMeteoParser.parseGeocoding("""{"generationtime_ms":0.2}""").isEmpty())
    }
}

class EnsoTest {
    private val sample = """
        SEAS  YR   TOTAL   ANOM
          MAM 2026  28.09   0.46
          AMJ 2026  28.74   0.95
          MJJ 2026  29.02   1.39
          JJA 2026  29.09   1.80
          JAS 2026  29.12   2.16
    """.trimIndent()

    @Test fun parsesOniFile() {
        val r = EnsoParser.parse(sample)
        assertEquals(5, r.size)
        assertEquals("JAS", r.last().season)
        assertEquals(2.16, r.last().anomaly, 1e-9)
    }

    @Test fun classifiesVeryStrongElNino() {
        val s = EnsoAdvisor.status(EnsoParser.parse(sample))!!
        assertEquals(EnsoPhase.EL_NINO, s.phase)
        assertEquals(EnsoStrength.VERY_STRONG, s.strength)
        assertEquals(EnsoTrend.RISING, s.trend)
        val impact = EnsoAdvisor.kenyaImpact(s, 10)
        assertTrue(impact.outlook.contains("short rains"))
        assertTrue(impact.actions.isNotEmpty())
    }

    @Test fun neutralAndLaNina() {
        fun st(a: Double) = EnsoAdvisor.status(EnsoParser.parse("DJF 2026 26.0 $a"))!!
        assertEquals(EnsoPhase.NEUTRAL, st(0.2).phase)
        assertEquals(EnsoPhase.LA_NINA, st(-0.8).phase)
        assertEquals(EnsoStrength.WEAK, st(-0.8).strength)
        assertEquals(EnsoStrength.MODERATE, st(1.2).strength)
        assertEquals(EnsoStrength.STRONG, st(1.6).strength)
    }

    @Test fun emptyFileFails() {
        try { EnsoParser.parse("nothing here"); throw AssertionError("expected") } catch (e: ApiException) { }
    }
}

class RadarAndMonthlyTest {
    @Test fun radarFramesParsedAndSorted() {
        val body = """{"version":"2.0","generated":1,"host":"https://tilecache.rainviewer.com",
          "radar":{"past":[{"time":200,"path":"/v2/radar/b"},{"time":100,"path":"/v2/radar/a"}],
                   "nowcast":[{"time":300,"path":"/v2/radar/c"}]}}"""
        val r = RainViewerParser.parse(body)
        assertEquals(listOf(100L, 200L, 300L), r.frames.map { it.timeEpochSec })
        assertTrue(r.frames.last().nowcast)
    }

    @Test fun radarWithoutFramesFails() {
        try { RainViewerParser.parse("""{"radar":{"past":[]}}"""); throw AssertionError("expected") } catch (e: ApiException) { }
    }

    @Test fun partialMonthIsExtrapolatedAndAnomalyMatchesBaseline() {
        // 20 days left in October at 2 mm/day vs a 62 mm baseline (=2 mm/day) -> zero anomaly
        val days = (12..31).map { SeasonalDay(LocalDate.of(2026, 10, it), 27.0, 15.0, 2.0) }
        val clim = Climatology(DoubleArray(12) { 21.0 }, DoubleArray(12) { 62.0 }, "t")
        val m = MonthlyOutlookBuilder.build(days, clim).single()
        assertEquals(YearMonth.of(2026, 10), m.month)
        assertTrue(m.partial)
        assertEquals(62.0, m.precipMm, 1e-6)
        assertEquals(0.0, m.precipAnomalyPct!!, 1e-6)
        assertEquals(0.0, m.tempAnomalyC!!, 1e-6)
    }

    @Test fun wetterThanBaselineShowsPositiveAnomaly() {
        val days = (1..30).map { SeasonalDay(LocalDate.of(2026, 11, it), 26.0, 16.0, 6.0) }
        val clim = Climatology(DoubleArray(12) { 20.0 }, DoubleArray(12) { 90.0 }, "t")
        val m = MonthlyOutlookBuilder.build(days, clim).single()
        assertEquals(100.0, m.precipAnomalyPct!!, 1e-6)     // 180 vs 90 mm
        assertEquals(1.0, m.tempAnomalyC!!, 1e-6)
    }

    @Test fun tinyCoverageIsDropped() {
        val days = (1..3).map { SeasonalDay(LocalDate.of(2026, 12, it), 26.0, 16.0, 1.0) }
        assertTrue(MonthlyOutlookBuilder.build(days, null).isEmpty())
    }

    @Test fun worksWithoutBaseline() {
        val days = (1..30).map { SeasonalDay(LocalDate.of(2026, 11, it), 26.0, 16.0, 3.0) }
        val m = MonthlyOutlookBuilder.build(days, null).single()
        assertNull(m.precipAnomalyPct)
        assertNotNull(m.tempMeanC)
        assertEquals(90.0, m.precipMm, 1e-6)
    }
}
