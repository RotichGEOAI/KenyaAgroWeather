package ke.geoai.agroweather

import ke.geoai.agroweather.data.model.Forecast
import ke.geoai.agroweather.data.parse.OpenMeteoParser
import ke.geoai.agroweather.domain.Analyzer
import ke.geoai.agroweather.domain.Light
import ke.geoai.agroweather.domain.RainAgreement
import ke.geoai.agroweather.domain.RainRiskAdvisor
import ke.geoai.agroweather.domain.SprayAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RainRiskTest {
    private val date = LocalDate.of(2026, 10, 13)
    private fun t(h: Int) = date.atTime(h, 0)

    /** Thick layered cloud, saturated air and very high storm energy, but the model's rain field is dry. */
    private fun stormySetup(h: Int, prob: Int? = 5) = hour(
        t(h), rh = 95.0, cloud = 95.0, cloudLow = 100.0, cloudMid = 80.0, cloudHigh = 60.0, cape = 2500.0, prob = prob,
    )

    @Test fun clearSkyAgreesWithDryModel() {
        val r = RainRiskAdvisor.assess(hour(t(15), rh = 50.0, cloud = 5.0, cloudLow = 0.0, cloudMid = 0.0, cloudHigh = 5.0, cape = 100.0))
        assertEquals(RainAgreement.CLEAR, r.agreement)
        assertTrue("sky=${r.skyChancePct}", r.skyChancePct < 25)
    }

    @Test fun flagsShowersTheModelRainFieldMisses() {
        val r = RainRiskAdvisor.assess(stormySetup(15))
        assertEquals(RainAgreement.MODEL_DRY_CLOUD_WET, r.agreement)
        assertTrue("sky=${r.skyChancePct}", r.skyChancePct >= 90)
        assertTrue("cautious=${r.cautiousChancePct}", r.cautiousChancePct >= 70)
    }

    @Test fun neverLowersTheModelChance() {
        val r = RainRiskAdvisor.assess(
            hour(t(15), rain = 2.0, prob = 85, rh = 40.0, cloud = 5.0, cloudLow = 0.0, cloudMid = 0.0, cloudHigh = 0.0),
        )
        assertEquals(RainAgreement.MODEL_WET_CLOUD_CLEAR, r.agreement)
        assertEquals(85, r.cautiousChancePct)
    }

    @Test fun modelAndCloudAgree() {
        val r = RainRiskAdvisor.assess(stormySetup(15, prob = 80).copy(precipMm = 2.0))
        assertEquals(RainAgreement.CONFIRMED, r.agreement)
    }

    @Test fun usesDiscountedTotalCloudWhenLayersMissing() {
        val cs = RainRiskAdvisor.cloudSupport(hour(t(15), cloud = 100.0))
        assertEquals(0.7, cs, 1e-9)
    }

    @Test fun missingCapeIsTreatedAsNoConvection() {
        assertEquals(0.0, RainRiskAdvisor.convective(hour(t(15))), 1e-9)
    }

    @Test fun nightConvectionWeighsLessThanAfternoon() {
        val night = RainRiskAdvisor.convective(hour(t(3), cape = 2000.0))
        val afternoon = RainRiskAdvisor.convective(hour(t(15), cape = 2000.0))
        assertTrue(night < afternoon)
        assertEquals(1.0, afternoon, 1e-9)
    }

    @Test fun stormEnergyLabels() {
        assertEquals("n/a", RainRiskAdvisor.energyLabel(null))
        assertEquals("Low", RainRiskAdvisor.energyLabel(150.0))
        assertEquals("Moderate", RainRiskAdvisor.energyLabel(600.0))
        assertEquals("High", RainRiskAdvisor.energyLabel(1800.0))
        assertEquals("Extreme", RainRiskAdvisor.energyLabel(3000.0))
    }

    @Test fun cardRaisesCloudAlertWhenModelIsDryButSkyIsLoaded() {
        val hours = (13..18).map { stormySetup(it) }
        val adv = RainRiskAdvisor.advise(RainRiskAdvisor.assess(hours), hours)
        assertEquals("CLOUD ALERT", adv.badge)
        assertEquals(Light.RED, adv.light)
        assertTrue(adv.headline, adv.headline.contains("13:00") && adv.headline.contains("18:00"))
    }

    @Test fun cardIsGreenOnAClearDryDay() {
        val hours = (6..20).map { hour(t(it), rh = 50.0, cloud = 5.0, cloudLow = 0.0, cloudMid = 0.0, cloudHigh = 5.0, cape = 50.0) }
        val adv = RainRiskAdvisor.advise(RainRiskAdvisor.assess(hours), hours)
        assertEquals("DRY", adv.badge)
        assertEquals(Light.GREEN, adv.light)
    }

    @Test fun cardHandlesNoData() {
        assertEquals("NO DATA", RainRiskAdvisor.advise(emptyList(), emptyList()).badge)
    }

    @Test fun sprayIsHeldWhenModelIsDryButStormEnergyIsHigh() {
        val hours = listOf(stormySetup(14), stormySetup(15), stormySetup(16))
        val a = SprayAdvisor.assess(hours, listOf(day(date)))
        assertEquals(Light.RED, a[0].light)
        assertTrue(a[0].reasons.toString(), a[0].reasons.any { it.contains("cloud and storm energy") })
    }

    @Test fun parserReadsCloudLayersAndCape() {
        val body = """{"latitude":0.5,"longitude":35.2,"elevation":2100,"utc_offset_seconds":10800,
          "hourly":{"time":["2026-10-12T14:00"],"temperature_2m":[22],"relative_humidity_2m":[88],
            "precipitation":[0.0],"wind_speed_10m":[6],"cloud_cover":[90],"cloud_cover_low":[70],
            "cloud_cover_mid":[55],"cloud_cover_high":[20],"cape":[1650]},
          "daily":{"time":["2026-10-12"],"temperature_2m_max":[26],"temperature_2m_min":[12],"precipitation_sum":[0]}}"""
        val h = OpenMeteoParser.parseForecast(body, 1L).hourly.single()
        assertEquals(70.0, h.cloudLowPct!!, 1e-9)
        assertEquals(55.0, h.cloudMidPct!!, 1e-9)
        assertEquals(20.0, h.cloudHighPct!!, 1e-9)
        assertEquals(1650.0, h.capeJkg!!, 1e-9)
    }

    @Test fun oldCachedForecastsWithoutCloudFieldsStillParse() {
        val body = """{"hourly":{"time":["2026-10-12T14:00"],"temperature_2m":[22],"relative_humidity_2m":[88],
            "precipitation":[0.0],"wind_speed_10m":[6]},
          "daily":{"time":["2026-10-12"],"temperature_2m_max":[26],"temperature_2m_min":[12],"precipitation_sum":[0]}}"""
        val h = OpenMeteoParser.parseForecast(body, 1L).hourly.single()
        assertEquals(null, h.cloudLowPct)
        assertEquals(null, h.capeJkg)
    }

    @Test fun analyzerRaisesStormAlertAndKeepsRainMillimetresUntouched() {
        val start = LocalDateTime.of(2026, 10, 12, 13, 0)
        val hours = (0 until 24 * 16).map { i ->
            val tt = start.plusHours(i.toLong())
            if (i < 6) stormySetup(0).copy(time = tt) else hour(tt)
        }
        val days = (-2 until 16).map { day(start.toLocalDate().plusDays(it.toLong())) }
        val fc = Forecast(0.5, 35.2, 2100.0, 10800, hours, days, 0L)
        val a = Analyzer.analyze(farm(), fc, null, start)

        assertTrue(a.alerts.map { it.id }.toString(), a.alerts.any { it.id.startsWith("STORM_RISK") })
        assertEquals("CLOUD ALERT", a.rainAdvice.badge)
        assertEquals(0.0, a.hours.take(6).sumOf { it.precipMm }, 1e-9)     // model rain field unchanged
        assertEquals(a.hours.size, a.rain.size)
    }
}
