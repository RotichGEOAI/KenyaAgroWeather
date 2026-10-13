package ke.geoai.agroweather.ui.components

import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.GpsFix
import ke.geoai.agroweather.ui.LocationHelper
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private class PickerHolder {
    var marker: Marker? = null
    var circle: Polygon? = null
    var flown: Pair<Double, Double>? = null
}

/**
 * Full-screen map: tap anywhere (zoom to 19, ~0.3 m/pixel at the equator) to place the pin, or use GPS and see the
 * reported accuracy radius. Coordinates are returned at 5 decimals (~1 m).
 */
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLon: Double?,
    onDismiss: () -> Unit,
    onPicked: (lat: Double, lon: Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { PickerHolder() }

    var picked by remember { mutableStateOf(if (initialLat != null && initialLon != null) initialLat to initialLon else null) }
    var fix by remember { mutableStateOf<GpsFix?>(null) }
    var flyTo by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            if (initialLat != null && initialLon != null) {
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(initialLat, initialLon))
            } else {
                controller.setZoom(6.0)
                controller.setCenter(GeoPoint(0.0236, 37.9062)) // centre of Kenya
            }
            overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    picked = p.latitude to p.longitude
                    fix = null
                    message = null
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
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
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }

    fun locate() {
        scope.launch {
            locating = true
            message = null
            try {
                val f = LocationHelper.fix(context)
                if (f == null) message = "No GPS fix yet. Step into the open and try again, or tap the map."
                else {
                    fix = f
                    picked = f.lat to f.lon
                    flyTo = f.lat to f.lon
                    message = LocationHelper.describe(f.accuracyM)
                }
            } catch (e: SecurityException) {
                message = "Location permission is needed."
            } catch (e: Exception) {
                message = "Couldn't get a GPS fix. Tap the map instead."
            }
            locating = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else message = "Location permission denied. Tap the map to place the pin."
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding()) {
                Text(
                    "Tap the map to place your spot", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { map },
                    update = { mv -> syncPicker(mv, holder, picked, fix, flyTo) },
                )
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val p = picked
                    Text(
                        if (p != null) "📍 ${Fmt.coord(p.first)}, ${Fmt.coord(p.second)}" else "No spot chosen yet",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Zoom in for precision (pin position is stored to about 1 m). Weather models work on a grid of " +
                            "several km, so conditions are the same across a field; the exact pin sets the terrain " +
                            "elevation used for temperature.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                    OutlinedButton(
                        enabled = !locating,
                        onClick = {
                            if (LocationHelper.hasAnyPermission(context)) locate()
                            else permLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (locating) "Getting GPS fix…" else "🎯 Use GPS (high accuracy)") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        Button(
                            enabled = p != null,
                            onClick = { p?.let { onPicked(it.first, it.second) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Use this spot") }
                    }
                }
            }
        }
    }
}

private fun syncPicker(
    mv: MapView,
    holder: PickerHolder,
    picked: Pair<Double, Double>?,
    fix: GpsFix?,
    flyTo: Pair<Double, Double>?,
) {
    holder.marker?.let { mv.overlays.remove(it) }
    holder.circle?.let { mv.overlays.remove(it) }
    holder.marker = null
    holder.circle = null

    if (picked != null) {
        val point = GeoPoint(picked.first, picked.second)
        if (fix != null && fix.accuracyM > 0f) {
            val circle = Polygon(mv).apply {
                points = Polygon.pointsAsCircle(point, fix.accuracyM.toDouble())
                fillPaint.color = AColor.argb(50, 30, 136, 229)
                outlinePaint.color = AColor.argb(200, 30, 136, 229)
                outlinePaint.strokeWidth = 3f
            }
            holder.circle = circle
            mv.overlays.add(circle)
        }
        val marker = Marker(mv).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
        }
        holder.marker = marker
        mv.overlays.add(marker)
    }

    if (flyTo != null && holder.flown != flyTo) {
        holder.flown = flyTo
        mv.controller.setZoom(18.0)
        mv.controller.animateTo(GeoPoint(flyTo.first, flyTo.second))
    }
    mv.invalidate()
}
