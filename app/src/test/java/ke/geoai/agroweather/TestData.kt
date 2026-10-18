package ke.geoai.agroweather

import ke.geoai.agroweather.data.model.Crop
import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.FarmProfile
import ke.geoai.agroweather.data.model.HourPoint
import ke.geoai.agroweather.data.model.SoilMoistureLayers
import ke.geoai.agroweather.data.model.SoilType
import java.time.LocalDate
import java.time.LocalDateTime

fun hour(
    t: LocalDateTime,
    temp: Double = 24.0,
    rh: Double = 60.0,
    rain: Double = 0.0,
    prob: Int? = 0,
    wind: Double = 8.0,
    gust: Double = 14.0,
    cloudLow: Double? = null,
    cloudMid: Double? = null,
    cloudHigh: Double? = null,
    cape: Double? = null,
    cloud: Double = 20.0,
) = HourPoint(
    time = t, tempC = temp, humidityPct = rh, dewPointC = null, precipMm = rain, precipProbPct = prob,
    windKmh = wind, gustKmh = gust, windDirDeg = 90.0, cloudPct = cloud, et0Mm = 0.3,
    soilMoisture = SoilMoistureLayers(0.25, 0.25, 0.26, 0.28), soilTempC = 20.0,
    cloudLowPct = cloudLow, cloudMidPct = cloudMid, cloudHighPct = cloudHigh, capeJkg = cape,
)

fun day(
    date: LocalDate,
    tMax: Double = 27.0,
    tMin: Double = 15.0,
    rain: Double = 0.0,
    et0: Double? = 5.0,
    gust: Double = 20.0,
) = DayPoint(
    date = date, tMaxC = tMax, tMinC = tMin, precipMm = rain, precipProbMaxPct = if (rain > 0) 80 else 5,
    windMaxKmh = gust * 0.6, gustMaxKmh = gust, et0Mm = et0,
    sunrise = date.atTime(6, 15), sunset = date.atTime(18, 30),
)

fun farm(
    crop: Crop = Crop.MAIZE,
    soil: SoilType = SoilType.LOAM,
    planting: LocalDate? = null,
) = FarmProfile(
    id = 1, name = "Test farm", lat = 0.5, lon = 35.3, crop = crop, soil = soil,
    plantingDate = planting, probeVwc = null, probeAtMs = null,
)
