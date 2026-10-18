package ke.geoai.agroweather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ke.geoai.agroweather.AgroWeatherApp
import ke.geoai.agroweather.data.db.toEntity
import ke.geoai.agroweather.data.db.toProfile
import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.model.Crop
import ke.geoai.agroweather.data.model.SoilType
import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.FarmProfile
import ke.geoai.agroweather.data.model.Forecast
import ke.geoai.agroweather.data.model.GeoPlace
import ke.geoai.agroweather.data.model.ModelResult
import ke.geoai.agroweather.data.model.MonthOutlook
import ke.geoai.agroweather.data.model.RadarFrames
import ke.geoai.agroweather.data.model.SeasonalDay
import ke.geoai.agroweather.domain.Analysis
import ke.geoai.agroweather.domain.Analyzer
import ke.geoai.agroweather.domain.EnsoAdvisor
import ke.geoai.agroweather.domain.EnsoStatus
import ke.geoai.agroweather.domain.MonthlyOutlookBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

data class SpotState(
    val lat: Double,
    val lon: Double,
    val loading: Boolean = true,
    val error: String? = null,
    val analysis: Analysis? = null,
    val elevationM: Double? = null,
)

data class UiState(
    val farms: List<FarmProfile> = emptyList(),
    val selected: FarmProfile? = null,
    val farmsLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val stale: Boolean = false,
    val updatedAtMs: Long? = null,
    val forecast: Forecast? = null,
    val analysis: Analysis? = null,
    val clim: Climatology? = null,
    val seasonal: List<SeasonalDay> = emptyList(),
    val months: List<MonthOutlook> = emptyList(),
    val seasonalError: String? = null,
    val enso: EnsoStatus? = null,
    val ensoError: String? = null,
    val radar: RadarFrames? = null,
    val radarError: String? = null,
    val places: List<GeoPlace> = emptyList(),
    val searching: Boolean = false,
    val alertsEnabled: Boolean = true,
    val spot: SpotState? = null,
    val models: List<ModelResult> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsFor: Pair<Double, Double>? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as AgroWeatherApp).container
    private val _ui = MutableStateFlow(UiState(alertsEnabled = c.prefs.alertsEnabled))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var farmsCache: List<FarmProfile> = emptyList()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var spotJob: Job? = null
    private var modelsJob: Job? = null

    init {
        viewModelScope.launch {
            c.farms.observeAll().collect { entities ->
                farmsCache = entities.map { it.toProfile() }
                applyFarms()
            }
        }
        loadGlobal(force = false)
    }

    private fun applyFarms() {
        val farms = farmsCache
        val old = _ui.value.selected
        val sel = farms.firstOrNull { it.id == c.prefs.selectedFarmId } ?: farms.firstOrNull()
        val moved = sel == null || old == null || old.id != sel.id || old.lat != sel.lat || old.lon != sel.lon
        _ui.update { s ->
            val base = s.copy(farms = farms, selected = sel, farmsLoaded = true)
            if (moved) base.copy(
                models = emptyList(), modelsFor = null, modelsLoading = false,
                loading = false, forecast = null, analysis = null, clim = null, seasonal = emptyList(),
                months = emptyList(), error = null, seasonalError = null, stale = false, updatedAtMs = null,
            ) else base
        }
        when {
            sel == null -> loadJob?.cancel()
            moved -> load(force = false)
            else -> recompute()
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Weather service error"
        is IOException -> "No internet connection"
        else -> "Something went wrong. Please try again."
    }

    fun refresh() {
        load(force = true)
        loadGlobal(force = true)
    }

    private fun load(force: Boolean) {
        val farm = _ui.value.selected ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            coroutineScope {
                launch {
                    attempt { c.repo.forecast(farm.lat, farm.lon, force) }
                        .onSuccess { l ->
                            _ui.update {
                                it.copy(
                                    forecast = l.data, stale = l.stale, updatedAtMs = l.fetchedAtMs,
                                    error = if (l.stale) "Offline – showing the forecast saved ${Fmt.ago(l.fetchedAtMs)}" else null,
                                )
                            }
                            recompute()
                        }
                        .onFailure { e -> _ui.update { it.copy(error = friendly(e)) } }
                }
                launch {
                    attempt { c.repo.climatology(farm.lat, farm.lon) }
                        .onSuccess { l -> _ui.update { it.copy(clim = l.data) }; recompute() }
                }
                launch {
                    attempt { c.repo.seasonal(farm.lat, farm.lon, force) }
                        .onSuccess { l -> _ui.update { it.copy(seasonal = l.data, seasonalError = null) }; recompute() }
                        .onFailure { e -> _ui.update { it.copy(seasonalError = friendly(e)) } }
                }
            }
            _ui.update { it.copy(loading = false) }
        }
    }

    /** Fetches the several-model comparison for the selected farm (on demand: it is 4 extra requests). */
    fun loadModels(force: Boolean = false) {
        val farm = _ui.value.selected ?: return
        val key = farm.lat to farm.lon
        if (!force && _ui.value.modelsFor == key && _ui.value.models.isNotEmpty()) return
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            _ui.update { it.copy(modelsLoading = true) }
            val r = attempt { c.repo.compareModels(farm.lat, farm.lon, force) }
            _ui.update {
                it.copy(models = r.getOrDefault(emptyList()), modelsFor = key.takeIf { r.isSuccess }, modelsLoading = false)
            }
        }
    }

    /** ENSO and radar do not depend on the selected farm. */
    private fun loadGlobal(force: Boolean) {
        viewModelScope.launch {
            attempt { c.repo.enso(force) }
                .onSuccess { l -> _ui.update { it.copy(enso = EnsoAdvisor.status(l.data), ensoError = null) } }
                .onFailure { e -> _ui.update { it.copy(ensoError = friendly(e)) } }
        }
        refreshRadar(force)
    }

    fun refreshRadar(force: Boolean = false) {
        viewModelScope.launch {
            attempt { c.repo.radar(force) }
                .onSuccess { l -> _ui.update { it.copy(radar = l.data, radarError = null) } }
                .onFailure { e -> _ui.update { it.copy(radarError = friendly(e)) } }
        }
    }

    private fun recompute() {
        val s = _ui.value
        val farm = s.selected ?: return
        val fc = s.forecast ?: return
        val analysis = Analyzer.analyze(farm, fc, s.clim, fc.localNow())
        val months = MonthlyOutlookBuilder.build(s.seasonal, s.clim)
        _ui.update { it.copy(analysis = analysis, months = months) }
    }

    // ---- farms ----------------------------------------------------------------------------

    fun select(id: Long) {
        c.prefs.selectedFarmId = id
        applyFarms()
    }

    fun saveFarm(p: FarmProfile) {
        viewModelScope.launch {
            val rowId = c.farms.upsert(p.toEntity())
            val id = if (p.id == 0L) rowId else p.id
            if (p.id == 0L || _ui.value.selected == null) c.prefs.selectedFarmId = id
            // the Room flow re-emits and applyFarms() picks the selection up
        }
    }

    fun deleteFarm(id: Long) {
        viewModelScope.launch {
            c.farms.delete(id)
            if (c.prefs.selectedFarmId == id) c.prefs.selectedFarmId = -1L
        }
    }

    fun logProbe(vwc: Double?) {
        val farm = _ui.value.selected ?: return
        saveFarm(farm.copy(probeVwc = vwc, probeAtMs = if (vwc != null) System.currentTimeMillis() else null))
    }

    fun setAlertsEnabled(on: Boolean) {
        c.prefs.alertsEnabled = on
        _ui.update { it.copy(alertsEnabled = on) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _ui.update { it.copy(places = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true) }
            val r = attempt { c.repo.searchPlaces(query) }
            _ui.update { it.copy(places = r.getOrDefault(emptyList()), searching = false) }
        }
    }

    fun clearSearch() = _ui.update { it.copy(places = emptyList(), searching = false) }

    // ---- tap-to-check any spot ----------------------------------------------------------------

    /** Conditions at a tapped point, using the selected farm's crop and soil (maize on loam if none). */
    fun checkSpot(lat: Double, lon: Double) {
        spotJob?.cancel()
        _ui.update { it.copy(spot = SpotState(lat, lon)) }
        spotJob = viewModelScope.launch {
            val base = _ui.value.selected
            val profile = FarmProfile(
                id = 0, name = "Pinned spot", lat = lat, lon = lon,
                crop = base?.crop ?: Crop.MAIZE, soil = base?.soil ?: SoilType.LOAM,
                plantingDate = base?.plantingDate, probeVwc = null, probeAtMs = null,
            )
            attempt { c.repo.forecast(lat, lon, false) }
                .onSuccess { l ->
                    val a = Analyzer.analyze(profile, l.data, null, l.data.localNow())
                    _ui.update { it.copy(spot = SpotState(lat, lon, false, null, a, l.data.elevationM)) }
                }
                .onFailure { e -> _ui.update { it.copy(spot = SpotState(lat, lon, false, friendly(e))) } }
        }
    }

    fun clearSpot() {
        spotJob?.cancel()
        _ui.update { it.copy(spot = null) }
    }

    fun saveSpotAsFarm() {
        val s = _ui.value.spot ?: return
        val base = _ui.value.selected
        saveFarm(
            FarmProfile(
                id = 0, name = "Pinned spot ${Fmt.coord(s.lat).take(8)}", lat = s.lat, lon = s.lon,
                crop = base?.crop ?: Crop.MAIZE, soil = base?.soil ?: SoilType.LOAM,
                plantingDate = null, probeVwc = null, probeAtMs = null,
            ),
        )
        clearSpot()
    }
}
