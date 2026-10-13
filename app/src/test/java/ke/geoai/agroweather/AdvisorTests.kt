package ke.geoai.agroweather

import ke.geoai.agroweather.data.model.Crop
import ke.geoai.agroweather.data.model.SoilType
import ke.geoai.agroweather.domain.AlertEngine
import ke.geoai.agroweather.domain.Light
import ke.geoai.agroweather.domain.Meteo
import ke.geoai.agroweather.domain.Severity
import ke.geoai.agroweather.domain.SprayAdvisor
import ke.geoai.agroweather.domain.WaterBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MeteoTest {
    @Test fun deltaTIsPlausible() {
        val dt = Meteo.deltaT(25.0, 50.0)
        assertTrue("deltaT=$dt", dt in 6.0..8.0)
    }

    @Test fun compassPoints() {
        assertEquals("N", Meteo.compass(0.0))
        assertEquals("N", Meteo.compass(359.0))
        assertEquals("E", Meteo.compass(90.0))
        assertEquals("SW", Meteo.compass(225.0))
    }
}

class SprayAdvisorTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val daily = listOf(day(day))
    private fun at(h: Int) = day.atTime(h, 0)

    private fun assess(vararg hours: ke.geoai.agroweather.data.model.HourPoint) =
        SprayAdvisor.assess(hours.toList(), daily)

    @Test fun calmMorningIsGreen() {
        val a = assess(hour(at(9)), hour(at(10)), hour(at(11)))
        assertEquals(Light.GREEN, a[0].light)
    }

    @Test fun windyIsRed() {
        val a = assess(hour(at(9), wind = 25.0, gust = 35.0), hour(at(10)), hour(at(11)))
        assertEquals(Light.RED, a[0].light)
    }

    @Test fun rainSoonIsRed() {
        val a = assess(hour(at(9)), hour(at(10), rain = 1.2, prob = 80), hour(at(11)))
        assertEquals(Light.RED, a[0].light)   // rain in the next 2 h
    }

    @Test fun nightIsRed() {
        val a = assess(hour(at(2)), hour(at(3)), hour(at(4)))
        assertEquals(Light.RED, a[0].light)
    }

    @Test fun nearCalmIsAmber() {
        val a = assess(hour(at(9), wind = 1.0, gust = 3.0), hour(at(10)), hour(at(11)))
        assertEquals(Light.AMBER, a[0].light)
    }

    @Test fun windowsNeedTwoHours() {
        val g = hour(at(9)); val bad = hour(at(10), wind = 30.0, gust = 40.0)
        val a = SprayAdvisor.assess(
            listOf(hour(at(7)), hour(at(8)), hour(at(9)), bad, hour(at(11))), daily,
        )
        val ws = SprayAdvisor.windows(a)
        assertTrue(ws.all { it.hours >= 2 })
    }

    @Test fun adviseWaitsForNextWindow() {
        val hours = listOf(
            hour(at(6), wind = 30.0, gust = 40.0), hour(at(7), wind = 30.0, gust = 40.0),
            hour(at(8)), hour(at(9)), hour(at(10)), hour(at(11)),
        )
        val adv = SprayAdvisor.advise(SprayAdvisor.assess(hours, daily))
        assertEquals("WAIT", adv.badge)
    }

    @Test fun adviseHoldsWhenStormy() {
        val hours = (6..14).map { hour(at(it), rain = 3.0, prob = 95) }
        val adv = SprayAdvisor.advise(SprayAdvisor.assess(hours, daily))
        assertEquals("HOLD", adv.badge)
    }
}

class WaterBalanceTest {
    private val today = LocalDate.of(2026, 10, 12)
    private fun dryDays(n: Int = 14, rain: Double = 0.0) = (0 until n).map { day(today.plusDays(it.toLong()), rain = rain, et0 = 6.0) }

    @Test fun effectiveRainThreshold() {
        assertEquals(0.0, WaterBalance.effectiveRain(1.5), 1e-9)
        assertEquals(8.5, WaterBalance.effectiveRain(12.0), 1e-9)
    }

    @Test fun cropCoefficientProfile() {
        assertEquals(0.30, Crop.MAIZE.kc(5), 1e-9)
        assertEquals(1.20, Crop.MAIZE.kc(60), 1e-9)
        assertTrue(Crop.MAIZE.kc(40) in 0.31..1.19)
        assertEquals(0.30, Crop.MAIZE.kc(-3), 1e-9)   // bare soil before planting
    }

    @Test fun wetSoilDryWeatherTriggersLater() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.minusDays(50))
        val p = WaterBalance.plan(f, dryDays(), theta0 = 0.30, source = WaterBalance.Source.MODEL, rainNext24hMm = 0.0, today = today)
        val idx = p.firstTriggerIndex
        assertNotNull(idx)
        assertTrue("trigger at $idx", idx!! in 7..13)
    }

    @Test fun wiltingPointTriggersToday() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.minusDays(50))
        val p = WaterBalance.plan(f, dryDays(), theta0 = 0.13, source = WaterBalance.Source.PROBE, rainNext24hMm = 0.0, today = today)
        assertEquals(0, p.firstTriggerIndex)
        assertTrue(p.recommendedMm!! in 1.0..40.0)
        assertEquals("IRRIGATE", WaterBalance.advise(p, f, today).badge)
    }

    @Test fun heavyRainPreventsTrigger() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.minusDays(50))
        val p = WaterBalance.plan(f, dryDays(rain = 25.0), theta0 = 0.22, source = WaterBalance.Source.MODEL, rainNext24hMm = 25.0, today = today)
        assertNull(p.firstTriggerIndex)
    }

    @Test fun rainComingHoldsIrrigation() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.minusDays(50))
        val p = WaterBalance.plan(f, dryDays(), theta0 = 0.13, source = WaterBalance.Source.PROBE, rainNext24hMm = 15.0, today = today)
        assertTrue(p.holdForRain)
        assertEquals("WAIT", WaterBalance.advise(p, f, today).badge)
    }

    @Test fun saturatedSoilSkipsIrrigation() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.minusDays(50))
        val p = WaterBalance.plan(f, dryDays(), theta0 = 0.33, source = WaterBalance.Source.MODEL, rainNext24hMm = 0.0, today = today)
        assertTrue(p.saturatedNow)
        assertEquals("WET", WaterBalance.advise(p, f, today).badge)
    }

    @Test fun notPlantedYetNeverIrrigates() {
        val f = farm(Crop.MAIZE, SoilType.LOAM, today.plusDays(10))
        val p = WaterBalance.plan(f, dryDays(), theta0 = 0.13, source = WaterBalance.Source.PROBE, rainNext24hMm = 0.0, today = today)
        assertTrue(p.notPlantedYet)
    }

    @Test fun sandySoilTriggersEarlierThanClay() {
        val planted = today.minusDays(50)
        val sandy = WaterBalance.plan(farm(Crop.MAIZE, SoilType.SANDY, planted), dryDays(), 0.18, WaterBalance.Source.MODEL, 0.0, today)
        val clay = WaterBalance.plan(farm(Crop.MAIZE, SoilType.CLAY, planted), dryDays(), 0.42, WaterBalance.Source.MODEL, 0.0, today)
        assertTrue(sandy.firstTriggerIndex!! < (clay.firstTriggerIndex ?: 99))
    }
}

class AlertEngineTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test fun veryHeavyRainIsWarning() {
        val days = listOf(day(today), day(today.plusDays(1), rain = 55.0), day(today.plusDays(2)))
        val alerts = AlertEngine.evaluate(emptyList(), days)
        val a = alerts.first { it.id.startsWith("HEAVY_RAIN") }
        assertEquals(Severity.WARNING, a.severity)
    }

    @Test fun threeDayTotalRaisesFloodRisk() {
        val days = (0..2).map { day(today.plusDays(it.toLong()), rain = 35.0) }
        val alerts = AlertEngine.evaluate(emptyList(), days)
        assertTrue(alerts.any { it.id.startsWith("FLOOD_RISK") && it.severity == Severity.WARNING })
    }

    @Test fun frostWarning() {
        val days = listOf(day(today, tMin = 1.0), day(today.plusDays(1)), day(today.plusDays(2)))
        val alerts = AlertEngine.evaluate(emptyList(), days)
        assertTrue(alerts.any { it.id.startsWith("COLD") && it.severity == Severity.WARNING })
    }

    @Test fun quietWeekHasNoAlerts() {
        val days = (0..6).map { day(today.plusDays(it.toLong()), rain = 3.0) }
        assertTrue(AlertEngine.evaluate(emptyList(), days).isEmpty())
    }

    @Test fun dryWeekGivesWatch() {
        val days = (0..6).map { day(today.plusDays(it.toLong()), rain = 0.0) }
        assertTrue(AlertEngine.evaluate(emptyList(), days).any { it.id.startsWith("DRY_SPELL") })
    }

    @Test fun intenseHourlyRainIsWarning() {
        val t = today.atTime(14, 0)
        val hours = listOf(hour(t, rain = 22.0, prob = 100))
        val alerts = AlertEngine.evaluate(hours, listOf(day(today)))
        assertTrue(alerts.any { it.id.startsWith("DOWNPOUR") })
    }
}

class KenyaTest {
    @Test fun seasonsCoverEveryMonth() {
        assertEquals("Long rains (Mar–May)", ke.geoai.agroweather.domain.KenyaSeason.of(4).name)
        assertEquals("Short rains (Oct–Dec)", ke.geoai.agroweather.domain.KenyaSeason.of(10).name)
        assertEquals(12, (1..12).map { ke.geoai.agroweather.domain.KenyaSeason.of(it).name }.size)
    }

    @Test fun quickPicksAreInsideKenya() {
        val places = ke.geoai.agroweather.data.KenyaPlaces.all
        assertTrue(places.size >= 15)
        assertTrue(places.all { it.lat in -4.8..5.1 && it.lon in 33.8..41.95 })
        assertEquals(places.size, places.map { it.name }.toSet().size)
    }

    @Test fun placeSearchIsLimitedToKenya() {
        assertTrue(ke.geoai.agroweather.data.parse.ApiUrls.geocode("Eldoret").contains("countryCode=KE"))
    }
}

class PrecisionTest {
    @Test fun pointForecastUsesMetreScaleCoordinates() {
        val url = ke.geoai.agroweather.data.parse.ApiUrls.forecast(0.514299, 35.269801)
        assertTrue(url, url.contains("latitude=0.5143&longitude=35.2698"))
    }

    @Test fun seasonalAndBaselineStayOnGridScale() {
        val u = ke.geoai.agroweather.data.parse.ApiUrls.seasonal(0.514299, 35.269801)
        assertTrue(u, u.contains("latitude=0.51&longitude=35.27"))
    }
}
