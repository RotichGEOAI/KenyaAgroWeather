package ke.geoai.agroweather.data.parse

import java.net.URLEncoder
import java.util.Locale

object ApiUrls {
    const val ENSO_ONI = "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt"
    const val RAINVIEWER = "https://api.rainviewer.com/public/weather-maps.json"

    private const val HOURLY = "temperature_2m,relative_humidity_2m,dew_point_2m,precipitation," +
        "precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,cloud_cover," +
        "et0_fao_evapotranspiration,soil_moisture_0_to_1cm,soil_moisture_1_to_3cm," +
        "soil_moisture_3_to_9cm,soil_moisture_9_to_27cm,soil_temperature_6cm"

    private const val DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum," +
        "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max," +
        "et0_fao_evapotranspiration,sunrise,sunset"

    /** Coordinates are rounded to 2 dp (~1 km): finer than the model grid and improves cache hits. */
    fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * Point forecasts use 4 decimals (~11 m). The model grid is km-scale, but Open-Meteo corrects temperature to the
     * terrain height at the exact point (90 m DEM), so a precise pin gives a better elevation correction.
     */
    fun fine(v: Double): String = String.format(Locale.US, "%.4f", v)

    fun forecast(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${fine(lat)}&longitude=${fine(lon)}" +
            "&hourly=$HOURLY&daily=$DAILY&forecast_days=16&past_days=2&timezone=auto&wind_speed_unit=kmh"

    fun seasonal(lat: Double, lon: Double) =
        "https://seasonal-api.open-meteo.com/v1/seasonal?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum"

    const val CLIMATOLOGY_START = 2011
    const val CLIMATOLOGY_END = 2025

    fun climatology(lat: Double, lon: Double) =
        "https://archive-api.open-meteo.com/v1/archive?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&start_date=$CLIMATOLOGY_START-01-01&end_date=$CLIMATOLOGY_END-12-31" +
            "&daily=temperature_2m_mean,precipitation_sum&timezone=auto"

    fun geocode(query: String) =
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&count=8&language=en&format=json&countryCode=KE"
}
