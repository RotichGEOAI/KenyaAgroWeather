package ke.geoai.agroweather.ui.screens

import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ke.geoai.agroweather.data.model.RadarFrame
import ke.geoai.agroweather.data.model.RadarFrames
import ke.geoai.agroweather.domain.Meteo
import ke.geoai.agroweather.ui.AppViewModel
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.SpotState
import ke.geoai.agroweather.ui.UiState
import ke.geoai.agroweather.ui.components.SignalBadge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import ke.geoai.agroweather.ui.components.NoteCard
import ke.geoai.agroweather.ui.components.StatTile
import kotlinx.coroutines.delay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant
import java.time.ZoneId

/** RainViewer tile layer for one radar frame. Tiles exist up to zoom 7; deeper zooms are scaled by osmdroid. */
private class RainTileSource(private val host: String, private val path: String, name: String) :
    OnlineTileSourceBase(name, 0, 7, 256, ".png", arrayOf(host)) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$host$path/256/${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/" +
            "${MapTileIndex.getY(pMapTileIndex)}/2/1_1.png"
}

/** Meteosat imagery from EUMETSAT's public EUMETView WMS (Indian Ocean satellite, which looks straight at Kenya). */
private enum class SatLayer(val label: String, val layer: String?) {
    OFF("Satellite off", null),
    IR("Cloud (infrared)", "msg_iodc:ir108"),
    CTH("Cloud-top height", "msg_iodc:cth"),
}

private class MeteosatTileSource(private val layer: String) :
    OnlineTileSourceBase("meteosat_$layer", 0, 8, 256, ".png", arrayOf("https://view.eumetsat.int/")) {
    override fun getTileURLString(pMapTileIndex: Long): String = ke.geoai.agroweather.data.parse.ApiUrls.eumetsatTile(
        layer, MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex),
    )
}

private class RadarOverlayHolder {
    var satOverlay: TilesOverlay? = null
    var satLayer: String? = null
    var provider: MapTileProviderBasic? = null
    var overlay: TilesOverlay? = null
    var shownPath: String? = null
    var farmMarker: Marker? = null
    var centredOn: Pair<Double, Double>? = null
    var spotMarker: Marker? = null
    var events: MapEventsOverlay? = null
    var onTap: (Double, Double) -> Unit = { _, _ -> }
}

@Composable
fun RadarScreen(ui: UiState, vm: AppViewModel, modifier: Modifier) {
    val farm = ui.selected
    val radar = ui.radar

    // Refresh the radar index whenever this tab is opened (4-minute cache in the repository).
    LaunchedEffect(Unit) { vm.refreshRadar(force = false) }

    var index by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(true) }
    var sat by rememberSaveable { mutableStateOf(SatLayer.OFF) }
    val wide = ke.geoai.agroweather.ui.rememberWidth() != ke.geoai.agroweather.ui.Width.COMPACT
    val frames = radar?.frames.orEmpty()
    // Start on the latest observed frame
    LaunchedEffect(frames.size) {
        if (frames.isNotEmpty()) index = frames.indexOfLast { !it.nowcast }.coerceAtLeast(0)
    }
    LaunchedEffect(playing, frames.size) {
        while (playing && frames.size > 1) {
            delay(700)
            index = (index + 1).mod(frames.size)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Rain radar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (farm == null) {
            NoteCard("Add a farm on the Farms tab to centre the radar on your fields.")
            return@Column
        }

        RadarMap(
            lat = farm.lat, lon = farm.lon, farmName = farm.name, radar = radar, frame = frames.getOrNull(index),
            spot = ui.spot?.let { it.lat to it.lon },
            satLayer = sat.layer,
            onTap = { la, lo -> vm.checkSpot(la, lo) },
            modifier = Modifier.fillMaxWidth().height(if (wide) 440.dp else 320.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SatLayer.values().forEach { l ->
                androidx.compose.material3.FilterChip(selected = sat == l, onClick = { sat = l }, label = { Text(l.label) })
            }
        }
        if (sat != SatLayer.OFF) {
            Text(
                "Meteosat (EUMETSAT), roughly every 15 minutes. Bright/white = high, cold cloud tops, the ones that bring heavy rain.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.spot == null) {
            Text(
                "👆 Tap anywhere on the map to check conditions at that spot.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.spot?.let { SpotCard(it, onSave = { vm.saveSpotAsFarm() }, onClear = { vm.clearSpot() }) }

        if (frames.isNotEmpty() && index in frames.indices) {
            val f = frames[index]
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = { playing = !playing }) { Text(if (playing) "⏸ Pause" else "▶ Play") }
                Text(
                    "  " + Fmt.hm.format(Instant.ofEpochSecond(f.timeEpochSec).atZone(ZoneId.systemDefault())) +
                        if (f.nowcast) " · forecast" else " · observed",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { playing = false; index = it.toInt().coerceIn(0, frames.lastIndex) },
                valueRange = 0f..frames.lastIndex.toFloat().coerceAtLeast(1f),
                steps = (frames.size - 2).coerceAtLeast(0),
            )
        } else {
            Text(
                ui.radarError ?: "Loading radar frames…",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Wind & temperature at the farm, from the model that assimilates radar, satellite and station data.
        val now = ui.analysis?.hours?.firstOrNull()
        if (now != null) {
            Text("At your farm now (model analysis)", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Wind", "${Fmt.n0(now.windKmh)} km/h", "from ${Meteo.compass(now.windDirDeg)} · gust ${Fmt.n0(now.gustKmh)}", Modifier.weight(1f))
                StatTile("Temperature", "${Fmt.n1(now.tempC)} °C", "Dew point ${now.dewPointC?.let { Fmt.n0(it) } ?: "—"} °C", Modifier.weight(1f))
            }
        }

        ui.analysis?.let { a ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SignalBadge(a.rainAdvice.light, a.rainAdvice.badge)
                Text("  ${a.rainAdvice.headline}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }

        NoteCard(
            "Radar shows where rain is falling now and the next ~30 minutes. Radar coverage is patchy over parts of " +
                "East Africa, so a blank area can mean no radar rather than no rain. Wind and temperature come from the " +
                "weather model, not from radar directly; so do cloud cover and storm energy. Radar: RainViewer · Satellite: Meteosat © EUMETSAT · Map: © OpenStreetMap contributors.",
        )
    }
}

@Composable
private fun RadarMap(
    lat: Double,
    lon: Double,
    farmName: String,
    radar: RadarFrames?,
    frame: RadarFrame?,
    spot: Pair<Double, Double>?,
    satLayer: String?,
    onTap: (Double, Double) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val holder = remember { RadarOverlayHolder() }
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 10.0
            controller.setZoom(7.0)
            isTilesScaledToDpi = true
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { map },
        update = { mv -> holder.onTap = onTap; updateMap(mv, context, holder, lat, lon, farmName, radar, frame, spot, satLayer) },
    )
}

private fun updateMap(
    mv: MapView,
    ctx: Context,
    holder: RadarOverlayHolder,
    lat: Double,
    lon: Double,
    farmName: String,
    radar: RadarFrames?,
    frame: RadarFrame?,
    spot: Pair<Double, Double>?,
    satLayer: String?,
) {
    if (holder.events == null) {
        val ev = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                holder.onTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        holder.events = ev
        mv.overlays.add(0, ev)
    }
    holder.spotMarker?.let { mv.overlays.remove(it) }
    holder.spotMarker = null
    if (spot != null) {
        val m = Marker(mv).apply {
            position = GeoPoint(spot.first, spot.second)
            title = "Pinned spot"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
        }
        holder.spotMarker = m
        mv.overlays.add(m)
    }

    // Centre on the farm when it changes (not on every animation frame)
    if (holder.centredOn != lat to lon) {
        mv.controller.setCenter(GeoPoint(lat, lon))
        holder.centredOn = lat to lon
        holder.farmMarker?.let { mv.overlays.remove(it) }
        val marker = Marker(mv).apply {
            position = GeoPoint(lat, lon)
            title = farmName
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        holder.farmMarker = marker
        mv.overlays.add(marker)
    }

    if (radar != null && frame != null && holder.shownPath != frame.path) {
        val source = RainTileSource(radar.host, frame.path, "rainviewer_${frame.timeEpochSec}")
        val provider = holder.provider
        if (provider == null) {
            val p = MapTileProviderBasic(ctx, source)
            val overlay = TilesOverlay(p, ctx).apply {
                loadingBackgroundColor = AColor.TRANSPARENT
                loadingLineColor = AColor.TRANSPARENT
            }
            holder.provider = p
            holder.overlay = overlay
            layoutLayers(mv, holder)
        } else {
            provider.tileSource = source
        }
        holder.shownPath = frame.path
    }

    if (holder.satLayer != satLayer) {
        holder.satLayer = satLayer
        holder.satOverlay?.let { mv.overlays.remove(it) }
        holder.satOverlay = null
        if (satLayer != null) {
            val p = MapTileProviderBasic(ctx, MeteosatTileSource(satLayer))
            holder.satOverlay = TilesOverlay(p, ctx).apply {
                loadingBackgroundColor = AColor.TRANSPARENT
                loadingLineColor = AColor.TRANSPARENT
                // semi-transparent so the base map stays readable under the cloud image
                setColorFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(1f, 1f, 1f, 0.6f) }))
            }
        }
        layoutLayers(mv, holder)
    }
    mv.invalidate()
}

/** Bottom to top: satellite, then radar, then tap handler / markers. */
private fun layoutLayers(mv: MapView, holder: RadarOverlayHolder) {
    holder.satOverlay?.let { mv.overlays.remove(it) }
    holder.overlay?.let { mv.overlays.remove(it) }
    var at = 0
    holder.satOverlay?.let { mv.overlays.add(at++, it) }
    holder.overlay?.let { mv.overlays.add(at, it) }
}

@Composable
private fun SpotCard(spot: SpotState, onSave: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📍 Pinned spot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${Fmt.coord(spot.lat)}, ${Fmt.coord(spot.lon)}" + (spot.elevationM?.let { " · ${Fmt.n0(it)} m elevation" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val a = spot.analysis
            when {
                spot.loading -> Text("Loading conditions…")
                spot.error != null -> Text(spot.error, color = MaterialTheme.colorScheme.error)
                a != null -> {
                    val now = a.hours.firstOrNull()
                    if (now != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Temperature", "${Fmt.n0(now.tempC)} °C", "Humidity ${Fmt.n0(now.humidityPct)} %", Modifier.weight(1f))
                            StatTile("Wind", "${Fmt.n0(now.windKmh)} km/h", "from ${Meteo.compass(now.windDirDeg)}", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Rain 24 h", "${Fmt.n1(a.hours.take(24).sumOf { it.precipMm })} mm", null, Modifier.weight(1f))
                            StatTile("Rain 7 days", "${Fmt.n0(a.outlook.rain7Mm)} mm", null, Modifier.weight(1f))
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SignalBadge(a.rainAdvice.light, a.rainAdvice.badge)
                        Text("  Rain: ${a.rainAdvice.headline}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SignalBadge(a.sprayAdvice.light, a.sprayAdvice.badge)
                        Text("  Spraying: ${a.sprayAdvice.headline}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SignalBadge(a.waterAdvice.light, a.waterAdvice.badge)
                        Text("  Irrigation: ${a.waterAdvice.headline}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Weather models use grid cells several km wide, so the numbers apply across the surrounding area; " +
                            "your exact pin sets the elevation used for temperature. Irrigation assumes your selected farm's crop and soil.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, enabled = spot.analysis != null) { Text("Save as farm") }
                TextButton(onClick = onClear) { Text("Clear pin") }
            }
        }
    }
}
