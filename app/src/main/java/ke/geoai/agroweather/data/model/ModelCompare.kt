package ke.geoai.agroweather.data.model

import java.time.LocalDateTime

/** A weather model that can be requested from Open-Meteo. [id] is the value of the `models=` parameter. */
data class WeatherModel(val id: String, val label: String, val provider: String) {
    companion object {
        /** Identifiers taken from Open-Meteo's own example URLs; a model the service rejects is shown as unavailable. */
        val DEFAULTS = listOf(
            WeatherModel("ecmwf_ifs", "ECMWF IFS", "ECMWF (Europe)"),
            WeatherModel("ncep_gfs_seamless", "GFS", "NOAA (USA)"),
            WeatherModel("dwd_icon_seamless", "ICON", "DWD (Germany)"),
            WeatherModel("ukmo_seamless", "UK Met Office", "UKMO (UK)"),
        )
    }
}

data class ModelHour(
    val time: LocalDateTime,
    val precipMm: Double?,
    val tempC: Double?,
    val windKmh: Double?,
    val cloudPct: Double?,
    val capeJkg: Double?,
)

/** One model's hourly series for a point. Hours a model does not cover are simply absent. */
data class ModelSeries(val model: WeatherModel, val hours: List<ModelHour>)

/** Result of asking one model: either a series or the reason it is unavailable. */
data class ModelResult(val model: WeatherModel, val series: ModelSeries?, val error: String?, val stale: Boolean = false)
