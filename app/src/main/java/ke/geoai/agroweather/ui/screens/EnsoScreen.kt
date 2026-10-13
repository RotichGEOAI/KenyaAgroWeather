package ke.geoai.agroweather.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ke.geoai.agroweather.domain.EnsoAdvisor
import ke.geoai.agroweather.domain.EnsoPhase
import ke.geoai.agroweather.domain.EnsoStatus
import ke.geoai.agroweather.ui.AppViewModel
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.UiState
import ke.geoai.agroweather.ui.components.NoteCard
import ke.geoai.agroweather.ui.components.SectionTitle
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

private val WARM = Color(0xFFE65100)
private val COOL = Color(0xFF1565C0)
private val NEUTRAL = Color(0xFF757575)

@Composable
fun EnsoScreen(ui: UiState, vm: AppViewModel, modifier: Modifier) {
    val e = ui.enso
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("El Niño / La Niña", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (e == null) {
            Text(ui.ensoError ?: "Loading ENSO status…", style = MaterialTheme.typography.bodyLarge)
            if (ui.ensoError != null) TextButton(onClick = { vm.refresh() }) { Text("Try again") }
            return@Column
        }

        val impact = EnsoAdvisor.kenyaImpact(e, LocalDate.now().monthValue)
        val phaseColor = when (e.phase) { EnsoPhase.EL_NINO -> WARM; EnsoPhase.LA_NINA -> COOL; EnsoPhase.NEUTRAL -> NEUTRAL }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("Current status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    e.phase.label + if (e.strength.label != "—") " · ${e.strength.label}" else "",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = phaseColor,
                )
                Text(
                    "Oceanic Niño Index ${Fmt.signed1(e.latest.anomaly)} °C for ${e.latest.season} ${e.latest.year} · ${e.trend.label}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "ONI is the 3-month average sea-surface temperature anomaly in the Niño-3.4 region (NOAA CPC). " +
                        "+0.5 °C or more is El Niño, −0.5 °C or less is La Niña.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("Last 12 overlapping seasons", style = MaterialTheme.typography.titleMedium)
                OniChart(e, Modifier.padding(top = 8.dp))
            }
        }

        SectionTitle("What it means for Kenya")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(impact.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(impact.outlook, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("Suggested farm actions")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                impact.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // Local, farm-specific view of the coming months
        if (ui.months.isNotEmpty()) {
            SectionTitle("Your farm's seasonal outlook")
            ui.months.take(4).forEach { m ->
                val p = m.precipAnomalyPct
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.month.format(m.month), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${Fmt.n0(m.precipMm)} mm" + (p?.let { " (${Fmt.signed0(it)} %)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        NoteCard(
            "ENSO shifts the odds, it does not guarantee a result: local topography and the Indian Ocean Dipole also " +
                "matter, and NOAA's official El Niño advisory needs ocean–atmosphere coupling as well as warm water. " +
                "Always check the Kenya Meteorological Department and ICPAC (GHACOF) seasonal forecasts for your county. " +
                "Data: NOAA Climate Prediction Center.",
        )
    }
}

@Composable
private fun OniChart(e: EnsoStatus, modifier: Modifier = Modifier) {
    val h = e.history
    if (h.isEmpty()) return
    val grid = MaterialTheme.colorScheme.outlineVariant
    val limit = max(2.0, h.maxOf { abs(it.anomaly) } + 0.2)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width
            val ht = size.height
            val mid = ht / 2
            val bw = w / h.size
            fun y(v: Double) = (mid - (v / limit * mid)).toFloat()

            drawLine(grid, Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
            drawLine(WARM.copy(alpha = 0.5f), Offset(0f, y(0.5)), Offset(w, y(0.5)), strokeWidth = 1.5f)
            drawLine(COOL.copy(alpha = 0.5f), Offset(0f, y(-0.5)), Offset(w, y(-0.5)), strokeWidth = 1.5f)

            h.forEachIndexed { i, r ->
                val color = when {
                    r.anomaly >= 0.5 -> WARM
                    r.anomaly <= -0.5 -> COOL
                    else -> NEUTRAL
                }
                val top = minOf(mid, y(r.anomaly))
                val height = abs(y(r.anomaly) - mid).coerceAtLeast(2f)
                drawRect(color, Offset(i * bw + bw * 0.15f, top), Size(bw * 0.7f, height))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            h.forEach {
                Text(
                    it.season, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        Text(
            "Orange: El Niño-level warmth (≥ +0.5 °C) · blue: La Niña-level cooling (≤ −0.5 °C) · grey: neutral",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
