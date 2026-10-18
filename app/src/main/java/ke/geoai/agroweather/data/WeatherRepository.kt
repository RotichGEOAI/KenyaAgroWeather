package ke.geoai.agroweather.data

import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.Forecast
import ke.geoai.agroweather.data.model.GeoPlace
import ke.geoai.agroweather.data.model.OniReading
import ke.geoai.agroweather.data.model.RadarFrames
import ke.geoai.agroweather.data.model.SeasonalDay
import ke.geoai.agroweather.data.net.HttpFetcher
import ke.geoai.agroweather.data.model.ModelResult
import ke.geoai.agroweather.data.model.TahmoRain
import ke.geoai.agroweather.data.parse.TahmoParser
import ke.geoai.agroweather.data.model.WeatherModel
import ke.geoai.agroweather.data.parse.ApiUrls
import ke.geoai.agroweather.data.model.ModelSource
import ke.geoai.agroweather.data.parse.MetNoParser
import ke.geoai.agroweather.data.parse.ModelParser
import ke.geoai.agroweather.data.parse.PirateParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ke.geoai.agroweather.data.parse.EnsoParser
import ke.geoai.agroweather.data.parse.OpenMeteoParser
import ke.geoai.agroweather.data.parse.RainViewerParser
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cache-first repository. Fresh cache is served without a request; when a request fails the last
 * good response is served flagged `stale`; a response is cached only after it parses successfully.
 */
class WeatherRepository(
    private val http: HttpFetcher,
    private val cache: CacheStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Loaded<T>(val data: T, val stale: Boolean, val fetchedAtMs: Long)

    private suspend fun <T> cached(
        key: String,
        ttlMs: Long,
        force: Boolean,
        url: String,
        normalize: (String) -> String = { it },
        parse: (String, Long) -> T,
    ): Loaded<T> {
        val now = clock()
        val hit = cache.get(key)
        if (hit != null && !force && now - hit.fetchedAt < ttlMs) {
            return Loaded(parse(hit.json, hit.fetchedAt), stale = false, fetchedAtMs = hit.fetchedAt)
        }
        return try {
            val stored = normalize(http.get(url))
            val parsed = parse(stored, now)           // throws on a bad body, so it is never cached
            cache.put(CacheRecord(key, stored, now))
            Loaded(parsed, stale = false, fetchedAtMs = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hit != null) Loaded(parse(hit.json, hit.fetchedAt), stale = true, fetchedAtMs = hit.fetchedAt) else throw e
        }
    }

    private fun place(lat: Double, lon: Double) = "${ApiUrls.coord(lat)},${ApiUrls.coord(lon)}"

    suspend fun forecast(lat: Double, lon: Double, force: Boolean = false): Loaded<Forecast> =
        cached("fc:${ApiUrls.fine(lat)},${ApiUrls.fine(lon)}", FORECAST_TTL, force, ApiUrls.forecast(lat, lon)) { body, at ->
            OpenMeteoParser.parseForecast(body, at)
        }

    /**
     * Asks each source separately (in parallel); a failure of one is reported on that model only.
     * [utcOffsetSeconds] puts UTC-based sources on the same local clock as the Open-Meteo series.
     */
    suspend fun compareModels(
        lat: Double, lon: Double, force: Boolean = false,
        models: List<WeatherModel> = WeatherModel.DEFAULTS,
        utcOffsetSeconds: Int = 3 * 3600,
        pirateKey: String? = null,
    ): List<ModelResult> = coroutineScope {
        models.map { m ->
            async {
                if (m.needsKey && pirateKey.isNullOrBlank()) {
                    return@async ModelResult(m, null, "Add your free key below to enable")
                }
                try {
                    val url = when (m.source) {
                        ModelSource.OPEN_METEO -> ApiUrls.model(lat, lon, m.id)
                        ModelSource.MET_NORWAY -> ApiUrls.metNo(lat, lon)
                        ModelSource.PIRATE_WEATHER -> ApiUrls.pirate(pirateKey!!, lat, lon)
                    }
                    val l = cached("mm:${m.id}:${ApiUrls.fine(lat)},${ApiUrls.fine(lon)}", FORECAST_TTL, force, url) { body, _ ->
                        when (m.source) {
                            ModelSource.OPEN_METEO -> ModelParser.parse(m, body)
                            ModelSource.MET_NORWAY -> MetNoParser.parse(m, body, utcOffsetSeconds)
                            ModelSource.PIRATE_WEATHER -> PirateParser.parse(m, body, utcOffsetSeconds)
                        }
                    }
                    ModelResult(m, l.data, null, l.stale)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // never echo e.message for keyed sources verbatim: a URL containing the key could appear in it
                    val msg = if (m.needsKey) "Key rejected, limit reached or service down" else (e.message ?: "Unavailable")
                    ModelResult(m, null, msg)
                }
            }
        }.awaitAll()
    }

    /**
     * Rain recorded by the TAHMO station nearest to the point over the last ~2 days (rain variable `pr`).
     * Returns null when no station has coordinates. Credentials go in an Authorization header, never the URL, and are
     * never part of a cache key or an error message.
     */
    suspend fun tahmoRain(
        lat: Double, lon: Double, key: String, secret: String, utcOffsetSeconds: Int, force: Boolean = false,
    ): Loaded<TahmoRain?> {
        val keyTag = key.hashCode()   // cache separation per account without storing the key
        val stations = cachedAuth("tahmo:stations:$keyTag", STATIONS_TTL, force, ApiUrls.tahmoStations(), key, secret) { b, _ ->
            TahmoParser.parseStations(b)
        }
        val near = TahmoParser.nearest(stations.data, lat, lon) ?: return Loaded(null, stations.stale, stations.fetchedAtMs)
        val now = java.time.Instant.ofEpochMilli(clock())
        val start = now.minusSeconds(60L * 60 * 60)   // 60 h covers yesterday and today so far in any Kenyan time zone
        val iso = java.time.format.DateTimeFormatter.ISO_INSTANT
        val rain = cachedAuth(
            "tahmo:rain:$keyTag:${near.first.code}", FORECAST_TTL, force,
            ApiUrls.tahmoMeasurements(near.first.code, iso.format(start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
                iso.format(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)), "pr"),
            key, secret,
        ) { b, _ -> TahmoParser.parseRain(b, utcOffsetSeconds) }
        return Loaded(TahmoRain(near.first, near.second, rain.data), rain.stale || stations.stale, rain.fetchedAtMs)
    }

    private suspend fun <T> cachedAuth(
        key: String, ttlMs: Long, force: Boolean, url: String, user: String, pass: String,
        parse: (String, Long) -> T,
    ): Loaded<T> {
        val now = clock()
        val hit = cache.get(key)
        if (hit != null && !force && now - hit.fetchedAt < ttlMs) return Loaded(parse(hit.json, hit.fetchedAt), false, hit.fetchedAt)
        return try {
            val body = http.getBasic(url, user, pass)
            val parsed = parse(body, now)
            cache.put(CacheRecord(key, body, now))
            Loaded(parsed, false, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hit != null) Loaded(parse(hit.json, hit.fetchedAt), true, hit.fetchedAt) else throw e
        }
    }

    suspend fun seasonal(lat: Double, lon: Double, force: Boolean = false): Loaded<List<SeasonalDay>> =
        cached("seas:${place(lat, lon)}", SEASONAL_TTL, force, ApiUrls.seasonal(lat, lon)) { body, _ ->
            OpenMeteoParser.parseSeasonal(body)
        }

    /** 15-year ERA5 baseline, stored in compact form and refreshed monthly. */
    suspend fun climatology(lat: Double, lon: Double): Loaded<Climatology> {
        val label = "ERA5 ${ApiUrls.CLIMATOLOGY_START}–${ApiUrls.CLIMATOLOGY_END}"
        return cached(
            "clim:${place(lat, lon)}", CLIMATOLOGY_TTL, false, ApiUrls.climatology(lat, lon),
            normalize = { OpenMeteoParser.climatologyToJson(OpenMeteoParser.parseClimatology(it, label)) },
        ) { stored, _ -> OpenMeteoParser.climatologyFromJson(stored) }
    }

    suspend fun enso(force: Boolean = false): Loaded<List<OniReading>> =
        cached("enso", ENSO_TTL, force, ApiUrls.ENSO_ONI) { body, _ -> EnsoParser.parse(body) }

    suspend fun radar(force: Boolean = false): Loaded<RadarFrames> =
        cached("radar", RADAR_TTL, force, ApiUrls.RAINVIEWER) { body, _ -> RainViewerParser.parse(body) }

    suspend fun searchPlaces(query: String): List<GeoPlace> {
        if (query.trim().length < 2) return emptyList()
        return OpenMeteoParser.parseGeocoding(http.get(ApiUrls.geocode(query)))
    }

    companion object {
        val FORECAST_TTL = TimeUnit.MINUTES.toMillis(30)
        val SEASONAL_TTL = TimeUnit.HOURS.toMillis(12)
        val ENSO_TTL = TimeUnit.HOURS.toMillis(12)
        val RADAR_TTL = TimeUnit.MINUTES.toMillis(4)
        val STATIONS_TTL = TimeUnit.HOURS.toMillis(24)
        val CLIMATOLOGY_TTL = TimeUnit.DAYS.toMillis(30)
    }
}
