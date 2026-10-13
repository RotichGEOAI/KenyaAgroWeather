package ke.geoai.agroweather.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ke.geoai.agroweather.data.KenyaPlaces
import ke.geoai.agroweather.data.model.Crop
import ke.geoai.agroweather.data.model.FarmProfile
import ke.geoai.agroweather.data.model.GeoPlace
import ke.geoai.agroweather.data.model.SoilType
import ke.geoai.agroweather.ui.AppViewModel
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.LocationHelper
import ke.geoai.agroweather.ui.components.MapPickerDialog
import ke.geoai.agroweather.ui.UiState
import ke.geoai.agroweather.ui.components.NoteCard
import ke.geoai.agroweather.ui.components.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun FarmsScreen(ui: UiState, vm: AppViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<FarmProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FarmProfile?>(null) }
    val context = LocalContext.current

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My farms", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { creating = true }) { Text("+ Add farm") }
            }
        }

        if (ui.farms.isEmpty() && ui.farmsLoaded) {
            item { NoteCard("No farms yet. Add one to get advice for that exact location.") }
        }

        items(ui.farms, key = { it.id }) { f ->
            val selected = ui.selected?.id == f.id
            Card(
                Modifier.fillMaxWidth().clickable { vm.select(f.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (selected) Text("● Selected", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "${f.crop.label} · ${f.soil.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${Fmt.coord(f.lat)}, ${Fmt.coord(f.lon)}" +
                            (f.plantingDate?.let { " · planted ${Fmt.day.format(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { editing = f }) { Text("Edit") }
                        TextButton(onClick = { deleting = f }) { Text("Delete") }
                    }
                }
            }
        }

        item { SectionTitle("Alerts") }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weather alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Heavy rain, flood risk, strong wind, frost, heat and irrigation reminders, checked about every 3 hours.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ui.alertsEnabled, onCheckedChange = { on ->
                        vm.setAlertsEnabled(on)
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
            }
        }

        item { SectionTitle("About the data") }
        item {
            NoteCard(
                "Forecasts: Open-Meteo (blends of national weather-model data, assimilating radar, satellite and station " +
                    "observations). Radar: RainViewer. El Niño: NOAA CPC. Soil moisture is a model estimate for the top ~27 cm " +
                    "unless you log a probe reading. Spray, irrigation and field-work advice uses standard agronomic rules " +
                    "of thumb (FAO-56 water balance, Delta-T). It supports your judgement; it does not replace product " +
                    "labels, local extension advice or official warnings from the Kenya Meteorological Department.",
            )
        }
    }

    if (creating) FarmEditor(null, ui, vm, onDismiss = { creating = false; vm.clearSearch() }, onSave = { vm.saveFarm(it); creating = false; vm.clearSearch() })
    editing?.let { f ->
        FarmEditor(f, ui, vm, onDismiss = { editing = null; vm.clearSearch() }, onSave = { vm.saveFarm(it); editing = null; vm.clearSearch() })
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This removes the farm and its saved settings from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteFarm(f.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun <T> Picker(label: String, value: String, options: List<T>, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value, Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

@Composable
private fun FarmEditor(
    initial: FarmProfile?,
    ui: UiState,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (FarmProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var lat by remember { mutableStateOf(initial?.lat?.let { Fmt.coord(it) } ?: "") }
    var lon by remember { mutableStateOf(initial?.lon?.let { Fmt.coord(it) } ?: "") }
    var crop by remember { mutableStateOf(initial?.crop ?: Crop.MAIZE) }
    var soil by remember { mutableStateOf(initial?.soil ?: SoilType.LOAM) }
    var planting by remember { mutableStateOf(initial?.plantingDate) }
    var probe by remember { mutableStateOf(initial?.probeVwc?.let { Fmt.n0(it * 100) } ?: "") }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    // Debounced place search
    LaunchedEffect(query) {
        if (query.isBlank()) vm.clearSearch() else {
            delay(400)
            vm.search(query)
        }
    }

    var gpsNote by remember { mutableStateOf<String?>(null) }
    var showMap by remember { mutableStateOf(false) }

    fun fetchLocation() {
        scope.launch {
            locating = true
            message = null
            gpsNote = null
            try {
                val f = LocationHelper.fix(context)
                if (f != null) {
                    lat = Fmt.coord(f.lat)
                    lon = Fmt.coord(f.lon)
                    gpsNote = LocationHelper.describe(f.accuracyM)
                } else message = "No GPS fix yet. Step into the open and retry, or pick the spot on the map."
            } catch (e: SecurityException) {
                message = "Location permission is needed."
            } catch (e: Exception) {
                message = "Couldn't get a GPS fix. Pick the spot on the map instead."
            }
            locating = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) fetchLocation() else message = "Location permission denied. Pick the spot on the map or search."
    }

    val latV = Fmt.parse(lat)
    val lonV = Fmt.parse(lon)
    val probeV = Fmt.parse(probe)
    val probeOk = probe.isBlank() || (probeV != null && probeV in 1.0..60.0)
    val valid = name.isNotBlank() && latV != null && latV in -90.0..90.0 && lonV != null && lonV in -180.0..180.0 && probeOk

    if (showMap) MapPickerDialog(
        initialLat = latV, initialLon = lonV,
        onDismiss = { showMap = false },
        onPicked = { la, lo -> lat = Fmt.coord(la); lon = Fmt.coord(lo); gpsNote = null; showMap = false },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add farm" else "Edit farm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("Farm name") }, modifier = Modifier.fillMaxWidth(),
                )

                Text("Quick pick (Kenya)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KenyaPlaces.all.forEach { p ->
                        FilterChip(selected = false, onClick = {
                            lat = Fmt.coord(p.lat)
                            lon = Fmt.coord(p.lon)
                            if (name.isBlank()) name = p.name
                        }, label = { Text(p.name) })
                    }
                }

                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search a town or place") }, modifier = Modifier.fillMaxWidth(),
                )
                if (ui.searching) Text("Searching…", style = MaterialTheme.typography.bodySmall)
                ui.places.take(5).forEach { p: GeoPlace ->
                    TextButton(onClick = {
                        lat = Fmt.coord(p.latitude)
                        lon = Fmt.coord(p.longitude)
                        if (name.isBlank()) name = p.name
                        query = ""
                        vm.clearSearch()
                    }) { Text(p.label, Modifier.fillMaxWidth()) }
                }

                OutlinedButton(
                    onClick = { showMap = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🗺️ Pick exact spot on map") }

                OutlinedButton(
                    enabled = !locating,
                    onClick = {
                        if (LocationHelper.hasAnyPermission(context)) fetchLocation()
                        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (locating) "Getting GPS fix…" else "🎯 Use GPS (high accuracy)") }
                gpsNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat, onValueChange = { lat = it }, singleLine = true, label = { Text("Latitude") },
                        isError = lat.isNotBlank() && (latV == null || latV !in -90.0..90.0),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lon, onValueChange = { lon = it }, singleLine = true, label = { Text("Longitude") },
                        isError = lon.isNotBlank() && (lonV == null || lonV !in -180.0..180.0),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }

                Picker("Crop", crop.label, Crop.values().toList(), { it.label }) { crop = it }
                Picker("Soil", soil.label, SoilType.values().toList(), { it.label }) { soil = it }

                Column {
                    Text("Planting date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val d = planting ?: LocalDate.now()
                            DatePickerDialog(context, { _, y, m, day -> planting = LocalDate.of(y, m + 1, day) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }) { Text(planting?.let { Fmt.day.format(it) + " " + it.year } ?: "Not set") }
                        if (planting != null) TextButton(onClick = { planting = null }) { Text("Clear") }
                    }
                }

                OutlinedTextField(
                    value = probe, onValueChange = { probe = it.take(5) }, singleLine = true,
                    label = { Text("Soil moisture probe reading, % (optional)") }, isError = !probeOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )

                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val newProbe = probeV?.let { it / 100.0 }
                val probeChanged = newProbe != initial?.probeVwc
                onSave(
                    FarmProfile(
                        id = initial?.id ?: 0L,
                        name = name.trim(),
                        lat = latV!!,
                        lon = lonV!!,
                        crop = crop,
                        soil = soil,
                        plantingDate = planting,
                        probeVwc = newProbe,
                        probeAtMs = when {
                            newProbe == null -> null
                            probeChanged -> System.currentTimeMillis()
                            else -> initial?.probeAtMs
                        },
                        alertsEnabled = initial?.alertsEnabled ?: true,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
