package ke.geoai.agroweather.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.geoai.agroweather.domain.Alert
import ke.geoai.agroweather.domain.KenyaSeason
import ke.geoai.agroweather.domain.Light
import ke.geoai.agroweather.domain.Meteo
import ke.geoai.agroweather.domain.RainAgreement
import ke.geoai.agroweather.domain.RainRisk
import ke.geoai.agroweather.domain.RainRiskAdvisor
import ke.geoai.agroweather.domain.Severity
import ke.geoai.agroweather.domain.SprayAdvisor
import ke.geoai.agroweather.ui.AppViewModel
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.Tab
import ke.geoai.agroweather.ui.UiState
import ke.geoai.agroweather.ui.components.AdviceCard
import ke.geoai.agroweather.ui.components.NoteCard
import ke.geoai.agroweather.ui.components.SectionTitle
import ke.geoai.agroweather.ui.components.StatTile
import ke.geoai.agroweather.ui.theme.signalColor

@Composable
fun TodayScreen(ui: UiState, vm: AppViewModel, modifier: Modifier, onGo: (Tab) -> Unit) {
    val farm = ui.selected
    if (farm == null) {
        EmptyState(
            modifier = modifier,
            loaded = ui.farmsLoaded,
            onAdd = { onGo(Tab.FARMS) },
        )
        return
    }

    var probeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(farm.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${farm.crop.label} · ${farm.soil.label}" +
                            (ui.forecast?.elevationM?.let { " · ${Fmt.n0(it)} m" } ?: "") +
                            "\n📍 ${Fmt.coord(farm.lat)}, ${Fmt.coord(farm.lon)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val season = KenyaSeason.of(java.time.LocalDate.now().monthValue)
                    Text("🇰🇪 ${season.name} · ${season.note}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    ui.updatedAtMs?.let {
                        Text("Updated ${Fmt.ago(it)}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (ui.loading) CircularProgressIndicator(Modifier.height(28.dp), strokeWidth = 3.dp)
                else TextButton(onClick = { vm.refresh() }) { Text("⟳ Refresh") }
            }
        }

        ui.error?.let { item { NoteCard(it) } }

        val a = ui.analysis
        if (a == null) {
            if (ui.loading) item { Text("Loading your farm forecast…", style = MaterialTheme.typography.bodyLarge) }
            return@LazyColumn
        }

        if (a.alerts.isNotEmpty()) {
            items(a.alerts, key = { it.id }) { AlertCard(it) }
        }

        item { NowTiles(ui) }

        item { SectionTitle("Decisions for ${farm.name}") }
        item { AdviceCard("☁️", "Cloud & rain confidence", a.rainAdvice) }
        item { RainStrip(a.rain.take(24)) }
        item { AdviceCard("🧴", "Spraying", a.sprayAdvice) }
        item {
            val good = a.spray.take(24).count { it.light == Light.GREEN }
            SprayStrip(a.spray.take(24), good)
        }
        item { AdviceCard("💧", "Irrigation", a.waterAdvice) }
        item {
            AdviceCard("🌱", "Soil moisture", a.soilAdvice, footer = {
                TextButton(onClick = { probeDialog = true }) { Text("Log probe reading") }
            })
        }
        item { AdviceCard("🚜", "Field work & planting", a.fieldAdvice) }

        ui.enso?.let { e ->
            item {
                Card(
                    Modifier.fillMaxWidth().clickable { onGo(Tab.ENSO) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🌊 ${e.phase.label}" + if (e.strength.label != "—") " · ${e.strength.label}" else "",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Oceanic Niño Index ${Fmt.signed1(e.latest.anomaly)} °C (${e.latest.season} ${e.latest.year}) · ${e.trend.label}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Tap for what it means for your season", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    if (probeDialog) ProbeDialog(
        current = farm.probeVwc,
        onDismiss = { probeDialog = false },
        onSave = { vm.logProbe(it); probeDialog = false },
    )
}

@Composable
private fun EmptyState(modifier: Modifier, loaded: Boolean, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!loaded) {
            CircularProgressIndicator()
            return@Column
        }
        Text("🌾", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Kenya AgroWeather", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Add your first farm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tell us where it is and what you grow. You'll get spray, irrigation and field-work advice for that exact spot.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text("Add farm") }
    }
}

@Composable
private fun AlertCard(alert: Alert) {
    val light = if (alert.severity == Severity.WARNING) Light.RED else Light.AMBER
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 4.dp).size(12.dp).clip(RoundedCornerShape(50))
                    .background(signalColor(light)),
            )
            Spacer(Modifier.padding(start = 10.dp))
            Column {
                Text(
                    (if (alert.severity == Severity.WARNING) "WARNING · " else "WATCH · ") + alert.title,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text(alert.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NowTiles(ui: UiState) {
    val a = ui.analysis ?: return
    val now = a.hours.firstOrNull() ?: return
    val rain24 = a.hours.take(24).sumOf { it.precipMm }
    val peakProb = a.hours.take(24).maxOfOrNull { it.precipProbPct ?: 0 } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Temperature", "${Fmt.n0(now.tempC)} °C", "Humidity ${Fmt.n0(now.humidityPct)} %", Modifier.weight(1f))
            StatTile("Wind", "${Fmt.n0(now.windKmh)} km/h", "from ${Meteo.compass(now.windDirDeg)} · gusts ${Fmt.n0(now.gustKmh)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Rain next 24 h", "${Fmt.n1(rain24)} mm", "Chance up to $peakProb %", Modifier.weight(1f))
            StatTile(
                "Rain next 7 days", "${Fmt.n0(a.outlook.rain7Mm)} mm",
                a.outlook.normal7Mm?.let { "Normal ${Fmt.n0(it)} mm" } ?: "—", Modifier.weight(1f),
            )
        }
        val peakCape = a.hours.take(24).mapNotNull { it.capeJkg }.maxOrNull()
        val layers = if (now.cloudLowPct != null && now.cloudMidPct != null && now.cloudHighPct != null)
            "low ${Fmt.n0(now.cloudLowPct)} · mid ${Fmt.n0(now.cloudMidPct)} · high ${Fmt.n0(now.cloudHighPct)}" else null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Cloud cover", "${Fmt.n0(now.cloudPct ?: 0.0)} %", layers, Modifier.weight(1f))
            StatTile(
                "Storm energy", RainRiskAdvisor.energyLabel(peakCape),
                peakCape?.let { "CAPE ${Fmt.n0(it)} J/kg (24 h peak)" }, Modifier.weight(1f),
            )
        }
    }
}

/** 24 hourly cells of rain chance. "!" marks hours where cloud and storm energy suggest rain the model does not show. */
@Composable
private fun RainStrip(risks: List<RainRisk>) {
    if (risks.isEmpty()) return
    Column {
        Text("Rain chance by hour, next 24 h", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            risks.forEach { r ->
                val light = when {
                    r.cautiousChancePct >= 60 -> Light.RED
                    r.cautiousChancePct >= 35 -> Light.AMBER
                    else -> Light.GREEN
                }
                Box(
                    Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(4.dp)).background(signalColor(light)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            r.agreement == RainAgreement.MODEL_DRY_CLOUD_WET -> "!"
                            light == Light.RED -> "☂"
                            light == Light.AMBER -> "~"
                            else -> "·"
                        },
                        fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { i ->
                risks.getOrNull(i)?.let {
                    Text(Fmt.hm.format(it.time), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "☂ likely · ~ possible · ! cloud and storm energy suggest rain the model's rain field doesn't show",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 24 hourly cells: colour + a G/A/R letter so it is readable without colour vision. */
@Composable
private fun SprayStrip(hours: List<SprayAdvisor.Assessment>, goodHours: Int) {
    if (hours.isEmpty()) return
    Column {
        Text("Spray conditions, next 24 h · $goodHours good hours", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            hours.forEach { h ->
                Box(
                    Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(4.dp)).background(signalColor(h.light)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (h.light) { Light.GREEN -> "✓"; Light.AMBER -> "~"; Light.RED -> "✕" },
                        fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { i ->
                hours.getOrNull(i)?.let {
                    Text(Fmt.hm.format(it.time), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProbeDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let { Fmt.n0(it * 100) } ?: "") }
    val parsed = Fmt.parse(text)
    val valid = text.isBlank() || (parsed != null && parsed in 1.0..60.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Soil moisture reading") },
        text = {
            Column {
                Text(
                    "Enter volumetric water content (%) from your probe. It replaces the model estimate for 3 days. " +
                        "Leave blank to clear.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(5) }, singleLine = true,
                    label = { Text("VWC %") }, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(parsed?.let { it / 100.0 }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
