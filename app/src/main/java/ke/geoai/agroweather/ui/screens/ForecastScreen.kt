package ke.geoai.agroweather.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ke.geoai.agroweather.data.model.DayPoint
import ke.geoai.agroweather.data.model.HourPoint
import ke.geoai.agroweather.data.model.MonthOutlook
import ke.geoai.agroweather.domain.Analysis
import ke.geoai.agroweather.domain.Light
import ke.geoai.agroweather.domain.Meteo
import ke.geoai.agroweather.domain.ModelAgreement
import ke.geoai.agroweather.domain.RainAgreement
import ke.geoai.agroweather.domain.RainRisk
import ke.geoai.agroweather.domain.SprayAdvisor
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.Tab
import ke.geoai.agroweather.ui.UiState
import ke.geoai.agroweather.ui.components.HourlyChart
import ke.geoai.agroweather.ui.components.ModelLegend
import ke.geoai.agroweather.ui.components.ModelLinesChart
import ke.geoai.agroweather.ui.components.ModelRainBars
import ke.geoai.agroweather.ui.components.NoteCard
import ke.geoai.agroweather.ui.components.RainBars
import ke.geoai.agroweather.ui.components.SectionTitle
import ke.geoai.agroweather.ui.components.SignalBadge
import ke.geoai.agroweather.ui.components.WaterPlanChart
import ke.geoai.agroweather.ui.theme.signalColor
import java.time.LocalDate

private enum class Horizon(val label: String) {
    HOURS("48 hours"), DAYS7("7 days"), DAYS14("14 days"), MONTHLY("Monthly"), MODELS("Models")
}

@Composable
fun ForecastScreen(ui: UiState, modifier: Modifier, onGo: (Tab) -> Unit, onLoadModels: (Boolean) -> Unit, onPirateKey: (String) -> Unit, onTahmo: (String, String) -> Unit) {
    var horizon by rememberSaveable { mutableStateOf(Horizon.HOURS) }
    val a = ui.analysis
    val farm = ui.selected
    LaunchedEffect(horizon, farm?.id, farm?.lat, farm?.lon) {
        if (horizon == Horizon.MODELS && farm != null) onLoadModels(false)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                farm?.let { "Forecast · ${it.name}" } ?: "Forecast",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Horizon.values().forEach { h ->
                    FilterChip(selected = horizon == h, onClick = { horizon = h }, label = { Text(h.label) })
                }
            }
        }

        if (farm == null) {
            item { NoteCard("Add a farm on the Farms tab to see its forecast.") }
            return@LazyColumn
        }
        ui.error?.let { item { NoteCard(it) } }

        when (horizon) {
            Horizon.HOURS -> if (a != null) hoursSection(a) else item { Loading(ui) }
            Horizon.DAYS7 -> if (a != null && ui.forecast != null) daysSection(ui, a, 7) else item { Loading(ui) }
            Horizon.DAYS14 -> if (a != null && ui.forecast != null) daysSection(ui, a, 14) else item { Loading(ui) }
            Horizon.MONTHLY -> monthlySection(ui, onGo)
            Horizon.MODELS -> modelsSection(ui, onLoadModels, onPirateKey, onTahmo)
        }
    }
}

@Composable
private fun Loading(ui: UiState) {
    Text(if (ui.loading) "Loading…" else "No forecast yet. Pull a refresh from the Today tab.")
}

// ---- 48 hours ---------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.hoursSection(a: Analysis) {
    item {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("Next 48 hours", style = MaterialTheme.typography.titleMedium)
                HourlyChart(a.hours, Modifier.padding(top = 8.dp))
            }
        }
    }
    item { SectionTitle("Hour by hour") }
    val n = minOf(a.hours.size, a.spray.size, a.rain.size)
    items((0 until n).toList(), key = { a.hours[it].time.toString() }) { i -> HourRow(a.hours[i], a.spray[i], a.rain[i]) }
}

@Composable
private fun HourRow(h: HourPoint, s: SprayAdvisor.Assessment, r: RainRisk) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(78.dp)) {
                Text(Fmt.hm.format(h.time), fontWeight = FontWeight.Bold)
                Text(h.time.dayOfWeek.name.take(3), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${Fmt.n0(h.tempC)}°", Modifier.width(44.dp), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) {
                Text(
                    if (h.precipMm >= 0.1) "☔ ${Fmt.n1(h.precipMm)} mm" + (h.precipProbPct?.let { " · $it %" } ?: "") else "No rain" +
                        (h.precipProbPct?.takeIf { it >= 20 }?.let { " ($it %)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "💨 ${Meteo.compass(h.windDirDeg)} ${Fmt.n0(h.windKmh)} km/h (g ${Fmt.n0(h.gustKmh)})" +
                        (h.cloudPct?.let { " · ☁ ${Fmt.n0(it)} %" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (r.agreement) {
                    RainAgreement.MODEL_DRY_CLOUD_WET -> Text(
                        "⚠ Cloud and storm energy favour showers (≈${r.cautiousChancePct} %)",
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    )
                    RainAgreement.MODEL_WET_CLOUD_CLEAR -> Text(
                        "ℹ Sky looks unsupportive of the modelled rain",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Unit
                }
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(signalColor(s.light)))
            Spacer(Modifier.width(6.dp))
            Text(
                when (s.light) { Light.GREEN -> "Spray OK"; Light.AMBER -> "Spray: care"; Light.RED -> "No spray" },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ---- 7 / 14 days ------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.daysSection(ui: UiState, a: Analysis, count: Int) {
    val fc = ui.forecast ?: return
    val today = a.now.toLocalDate()
    val days = fc.daysFrom(today, count)

    item {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                val total = days.sumOf { it.precipMm }
                val normal = if (count == 7) a.outlook.normal7Mm else a.outlook.normal14Mm
                Text("Rain: ${Fmt.n0(total)} mm in $count days", style = MaterialTheme.typography.titleMedium)
                if (normal != null && normal > 1) {
                    val pct = (total / normal - 1) * 100
                    Text(
                        "Normal for this period: ${Fmt.n0(normal)} mm (${Fmt.signed0(pct)} %)",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Crop water use (ET₀): ${Fmt.n0(days.sumOf { it.et0Mm ?: 0.0 })} mm",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RainBars(days.map { it.precipMm }, days.map { it.date.dayOfWeek.name.take(1) }, Modifier.padding(top = 8.dp))
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("Irrigation plan", style = MaterialTheme.typography.titleMedium)
                WaterPlanChart(a.water, Modifier.padding(top = 8.dp))
                val events = a.water.steps.filter { it.irrigationMm > 0 }
                Text(
                    if (events.isEmpty()) "No irrigation needed in this period."
                    else events.joinToString("\n") { "${Fmt.day.format(it.date)}: about ${Fmt.n0(it.irrigationMm)} mm" },
                    modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    item { SectionTitle("Day by day") }
    items(days, key = { it.date.toString() }) { d -> DayRow(d, today, a.outlook.sprayHoursByDate[d.date], a.outlook.cloudMeanByDate[d.date], a.outlook.capeMaxByDate[d.date]) }
}

@Composable
private fun DayRow(d: DayPoint, today: LocalDate, sprayHours: Int?, cloud: Int?, cape: Double?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (d.date == today) "Today" else Fmt.day.format(d.date),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text("${Fmt.n0(d.tMaxC)}° / ${Fmt.n0(d.tMinC)}°", style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "☔ ${Fmt.n1(d.precipMm)} mm" + (d.precipProbMaxPct?.let { " · $it %" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                d.gustMaxKmh?.let { Text("💨 gust ${Fmt.n0(it)}", style = MaterialTheme.typography.bodyMedium) }
                d.et0Mm?.let { Text("ET₀ ${Fmt.n1(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
            if (cloud != null) {
                Text(
                    "☁ Cloud ${cloud} % average" + if ((cape ?: 0.0) >= 1500.0) " · ⛈ high storm energy" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (sprayHours != null) {
                Text(
                    if (sprayHours == 0) "Spraying: no good hours" else "Spraying: $sprayHours good hours",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ---- monthly ----------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.monthlySection(ui: UiState, onGo: (Tab) -> Unit) {
    item {
        NoteCard(
            "Monthly figures come from the ECMWF seasonal ensemble, compared with a ${ui.clim?.label ?: "multi-year"} baseline. " +
                "Skill is modest beyond ~3 weeks: use them to anticipate trends, not to plan a specific day.",
        )
    }
    if (ui.months.isEmpty()) {
        item {
            Text(
                ui.seasonalError ?: if (ui.loading) "Loading seasonal outlook…" else "Seasonal outlook unavailable.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    items(ui.months, key = { it.month.toString() }) { m -> MonthCard(m) }
    item {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("🌊 How does El Niño fit in?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("See the El Niño tab for the current ENSO status and what it means for Kenyan seasons.",
                    style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = { onGo(Tab.ENSO) }) { Text("Open El Niño tab") }
            }
        }
    }
}

@Composable
private fun MonthCard(m: MonthOutlook) {
    val pAnom = m.precipAnomalyPct
    val rainLight = when {
        pAnom == null -> Light.AMBER
        pAnom >= 40 -> Light.RED          // much wetter: flood / disease risk
        pAnom <= -40 -> Light.RED         // much drier: drought risk
        kotlin.math.abs(pAnom) >= 20 -> Light.AMBER
        else -> Light.GREEN
    }
    val rainText = when {
        pAnom == null -> "No baseline"
        pAnom >= 20 -> "WETTER"
        pAnom <= -20 -> "DRIER"
        else -> "NEAR NORMAL"
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.month.format(m.month), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SignalBadge(rainLight, rainText)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "Rain ≈ ${Fmt.n0(m.precipMm)} mm" + (pAnom?.let { " (${Fmt.signed0(it)} % vs normal)" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Mean temperature ${Fmt.n1(m.tempMeanC)} °C" + (m.tempAnomalyC?.let { " (${Fmt.signed1(it)} °C vs normal)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.partial) {
                Text(
                    "Only ${m.coveredDays} days of this month are in the outlook; total is scaled to a full month.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}


// ---- several weather models side by side -------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.modelsSection(
    ui: UiState, onLoad: (Boolean) -> Unit, onPirateKey: (String) -> Unit, onTahmo: (String, String) -> Unit,
) {
    if (ui.modelsLoading && ui.models.isEmpty()) {
        item { Text("Asking each weather model…") }
        return
    }
    if (ui.models.isEmpty()) {
        item { NoteCard("No model data yet. Check your connection and try again.") }
        item { TextButton(onClick = { onLoad(true) }) { Text("Retry") } }
        return
    }
    val today = ui.forecast?.localNow()?.toLocalDate() ?: LocalDate.now()
    val days = ModelAgreement.daily(ui.models, today, 7)
    val ok = ui.models.count { it.series != null }

    item {
        NoteCard(
            "$ok of ${ui.models.size} models answered. " + ModelAgreement.headline(days) +
                " Models are independent forecasts of the same place: where they agree you can plan with more confidence.",
        )
    }
    val failed = ui.models.filter { it.series == null }
    if (failed.isNotEmpty()) {
        item {
            Text(
                "Unavailable: " + failed.joinToString { "${it.model.label} (${it.error ?: "no data"})" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { SectionTitle("Compare visually") }
    item { ModelCharts(ui.models, days, ui.forecast?.localNow() ?: java.time.LocalDateTime.now()) }
    item { SectionTitle("Daily rain (mm) by model") }
    items(days) { d -> ModelDayCard(d, today) }
    item { TahmoCheckCard(ui) }
    item { TahmoKeyCard(ui.hasTahmo, onTahmo) }
    item { PirateKeyCard(ui.hasPirateKey, onPirateKey) }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Wet day = 1 mm or more. Only models covering the whole day are counted.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onLoad(true) }) { Text("Refresh") }
        }
    }
}

@Composable
private fun ModelDayCard(d: ModelAgreement.Day, today: LocalDate) {
    val light = when (d.verdict) {
        ModelAgreement.Verdict.DRY -> Light.GREEN
        ModelAgreement.Verdict.WET -> Light.AMBER
        ModelAgreement.Verdict.SPLIT -> Light.AMBER
        ModelAgreement.Verdict.THIN -> Light.AMBER
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (d.date == today) "Today" else d.date.dayOfWeek.name.take(3).lowercase()
                        .replaceFirstChar { it.uppercase() } + " " + d.date.dayOfMonth,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                SignalBadge(light, d.verdict.label)
            }
            d.perModel.forEachIndexed { mi, m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = ke.geoai.agroweather.ui.components.ModelColors.of(mi)))
                            append("■ ")
                            pop()
                            append(m.modelLabel)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        if (m.rainMm == null) "–" else "${Fmt.n1(m.rainMm)} mm" +
                            (m.tMaxC?.let { " · max ${Fmt.n0(it)}°C" } ?: "") +
                            (m.windMaxKmh?.let { " · wind ${Fmt.n0(it)} km/h" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (d.meanMm != null && d.reporting >= 2) {
                Text(
                    "${d.wetCount} of ${d.reporting} models wet · range ${Fmt.n1(d.minMm ?: 0.0)}–${Fmt.n1(d.maxMm ?: 0.0)} mm · " +
                        "average ${Fmt.n1(d.meanMm)} mm",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}


@Composable
private fun ModelCharts(
    results: List<ke.geoai.agroweather.data.model.ModelResult>,
    days: List<ModelAgreement.Day>,
    now: java.time.LocalDateTime,
) {
    var metric by rememberSaveable { mutableStateOf(ModelAgreement.Metric.RAIN_CUM) }
    val ok = results.withIndex().filter { it.value.series != null }
    val from = now.withMinute(0).withSecond(0).withNano(0)
    val count = if (metric == ModelAgreement.Metric.RAIN_CUM) 24 * 7 else 72
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelAgreement.Metric.values().forEach { m ->
                    FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.label) })
                }
            }
            Text(
                if (metric == ModelAgreement.Metric.RAIN_CUM) "Rain building up over the next 7 days (mm): lines that fan out are the disagreement"
                else "${metric.label} (${metric.unit}) over the next 72 hours",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelLegend(ok.map { it.value.model.label }, colorIdx = ok.map { it.index })
            ModelLinesChart(
                ok.map { (_, r) -> ModelAgreement.hourlySeries(r.series!!.hours, metric, from, count) },
                ok.map { it.index },
                metric.unit,
            )
            Text("Rain by day, side by side", style = MaterialTheme.typography.labelLarge)
            ModelRainBars(days, results.size)
            ModelLegend(results.map { it.model.label })
        }
    }
}

@Composable
private fun PirateKeyCard(hasKey: Boolean, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pirate Weather key", style = MaterialTheme.typography.titleSmall)
            Text(
                if (hasKey) "A key is saved on this phone. Enter a new one to replace it, or save an empty box to remove it."
                else "Optional. Get a free key at pirateweather.net, subscribe to the forecast API, then paste it here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("API key") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onSave(text); text = "" }) { Text("Save and compare") }
        }
    }
}


/** Forecast vs what the nearest TAHMO ground station actually recorded. */
@Composable
private fun TahmoCheckCard(ui: UiState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Forecast vs ground station (TAHMO)", style = MaterialTheme.typography.titleSmall)
            val t = ui.tahmo
            when {
                !ui.hasTahmo -> Text(
                    "Add your TAHMO key and secret below to compare each model with rain recorded by the nearest station.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.tahmoLoading && t == null -> Text("Reading the nearest station…")
                ui.tahmoError != null -> Text(ui.tahmoError, style = MaterialTheme.typography.bodyMedium)
                t != null -> {
                    val now = ui.forecast?.localNow() ?: java.time.LocalDateTime.now()
                    val today = now.toLocalDate()
                    Text(
                        "Station ${t.station.code}${t.station.name?.let { " ($it)" } ?: ""} · ${Fmt.n1(t.distanceKm)} km from your pin",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (t.distanceKm > 25.0) {
                        Text(
                            "More than 25 km away: rain can differ a lot over that distance, so treat the comparison as a rough guide.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    listOf(
                        "Yesterday" to (today.minusDays(1) to 24),
                        "Today so far" to (today to now.hour),
                    ).forEach { (label, dh) ->
                        val (date, upto) = dh
                        if (upto <= 0) return@forEach
                        val obs = t.mmFor(date, upto)
                        Text("$label: station recorded ${Fmt.n1(obs)} mm", style = MaterialTheme.typography.labelLarge)
                        val checks = ModelAgreement.verify(ui.models, date, obs, upto)
                        if (checks.isEmpty()) {
                            Text("No model covers this whole period.", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        checks.forEach { c ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.modelLabel, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${Fmt.n1(c.forecastMm)} mm (${if (c.errorMm >= 0) "+" else ""}${Fmt.n1(c.errorMm)})",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "Station rain is summed from its quality-checked records and assumed to be mm per record. A gap in the station data lowers its total.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TahmoKeyCard(has: Boolean, onSave: (String, String) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TAHMO credentials", style = MaterialTheme.typography.titleSmall)
            Text(
                if (has) "Saved on this phone. Enter new ones to replace them, or save both boxes empty to remove them."
                else "From TAHMO / DKUT (dsail-info@dkut.ac.ke): the API key name and its secret/token. They are sent only to TAHMO and kept only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, label = { Text("API key name") },
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                value = secret, onValueChange = { secret = it }, singleLine = true, label = { Text("API secret / token") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onSave(key, secret); key = ""; secret = "" }) { Text("Save and check") }
        }
    }
}
