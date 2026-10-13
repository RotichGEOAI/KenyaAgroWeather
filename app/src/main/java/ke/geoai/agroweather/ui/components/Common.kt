package ke.geoai.agroweather.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.geoai.agroweather.data.model.HourPoint
import ke.geoai.agroweather.domain.Advice
import ke.geoai.agroweather.domain.Light
import ke.geoai.agroweather.domain.WaterBalance
import ke.geoai.agroweather.ui.Fmt
import ke.geoai.agroweather.ui.theme.onSignal
import ke.geoai.agroweather.ui.theme.signalColor
import kotlin.math.max

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun SignalBadge(light: Light, text: String) {
    Surface(color = signalColor(light), shape = RoundedCornerShape(50)) {
        Text(
            text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = onSignal(light), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

/** Decision card: coloured edge + text badge (never colour alone); tap to see the reasoning. */
@Composable
fun AdviceCard(
    emoji: String,
    title: String,
    advice: Advice,
    modifier: Modifier = Modifier,
    footer: @Composable (() -> Unit)? = null,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(signalColor(advice.light)),
            )
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    SignalBadge(advice.light, advice.badge)
                }
                Spacer(Modifier.height(8.dp))
                Text(advice.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                AnimatedVisibility(open) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        advice.details.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (advice.details.isNotEmpty()) {
                    Text(
                        if (open) "Hide details" else "Why? Tap for details",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
                footer?.invoke()
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NoteCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text, modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Temperature line over hourly rain bars, with a one-line legend underneath. */
@Composable
fun HourlyChart(hours: List<HourPoint>, modifier: Modifier = Modifier) {
    if (hours.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val bar = Color(0xFF1E88E5)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tMin = hours.minOf { it.tempC }
    val tMax = hours.maxOf { it.tempC }
    val range = max(tMax - tMin, 1.0)
    val maxRain = max(hours.maxOf { it.precipMm }, 2.0)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width
            val h = size.height
            val rainH = h * 0.35f
            val tempTop = 8f
            val tempBottom = h - rainH - 8f
            val bw = w / hours.size

            drawLine(grid, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
            hours.forEachIndexed { i, p ->
                val bh = (p.precipMm / maxRain * rainH).toFloat()
                if (bh > 0.5f) {
                    drawRect(bar.copy(alpha = 0.75f), Offset(i * bw + bw * 0.15f, h - bh), Size(bw * 0.7f, bh))
                }
            }
            val path = Path()
            hours.forEachIndexed { i, p ->
                val x = i * bw + bw / 2
                val y = tempTop + ((1 - (p.tempC - tMin) / range) * (tempBottom - tempTop)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Temp ${Fmt.n0(tMin)}–${Fmt.n0(tMax)} °C", style = MaterialTheme.typography.labelMedium, color = line)
            Text("Rain peak ${Fmt.n1(hours.maxOf { it.precipMm })} mm/h · total ${Fmt.n1(hours.sumOf { it.precipMm })} mm",
                style = MaterialTheme.typography.labelMedium, color = bar)
        }
    }
}

/** Simple vertical bars (e.g. daily rain) with the value on top is avoided to keep it legible; labels go below. */
@Composable
fun RainBars(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val bar = Color(0xFF1E88E5)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxV = max(values.max(), 5.0)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val bw = size.width / values.size
            drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            values.forEachIndexed { i, v ->
                val bh = (v / maxV * (size.height - 6f)).toFloat()
                if (bh > 0.5f) drawRect(bar, Offset(i * bw + bw * 0.2f, size.height - bh), Size(bw * 0.6f, bh))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

/** Root-zone depletion over the plan, with the irrigation trigger (RAW), the limit (TAW) and irrigation events. */
@Composable
fun WaterPlanChart(plan: WaterBalance.Plan, modifier: Modifier = Modifier) {
    if (plan.steps.isEmpty()) return
    val line = Color(0xFF1E88E5)
    val rawColor = signalColor(Light.AMBER)
    val tawColor = signalColor(Light.RED)
    val irrColor = signalColor(Light.GREEN)
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
    val maxY = max(plan.tawMm * 1.05, 1.0)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val w = size.width
            val h = size.height
            fun yOf(v: Double) = (h - (v / maxY * h)).toFloat().coerceIn(0f, h)
            val bw = w / plan.steps.size

            drawLine(rawColor, Offset(0f, yOf(plan.rawMm)), Offset(w, yOf(plan.rawMm)), strokeWidth = 3f, pathEffect = dash)
            drawLine(tawColor, Offset(0f, yOf(plan.tawMm)), Offset(w, yOf(plan.tawMm)), strokeWidth = 3f, pathEffect = dash)

            plan.steps.forEachIndexed { i, s ->
                if (s.irrigationMm > 0) {
                    drawRect(irrColor.copy(alpha = 0.35f), Offset(i * bw, 0f), Size(bw, h))
                }
            }
            val path = Path()
            plan.steps.forEachIndexed { i, s ->
                val x = i * bw + bw / 2
                val y = yOf(s.depletionEndMm)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        Text(
            "Blue: water deficit in the root zone · amber dashes: irrigate level (${Fmt.n0(plan.rawMm)} mm) · " +
                "red dashes: stress limit (${Fmt.n0(plan.tawMm)} mm) · green bands: irrigation days",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
