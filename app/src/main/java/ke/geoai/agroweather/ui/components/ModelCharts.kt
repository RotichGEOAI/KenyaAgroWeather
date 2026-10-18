package ke.geoai.agroweather.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ke.geoai.agroweather.domain.ModelAgreement
import ke.geoai.agroweather.ui.Fmt
import kotlin.math.max

/** Colour-blind-safe (Okabe-Ito) palette; lines also differ by dash pattern so colour is never the only cue. */
object ModelColors {
    val palette = listOf(
        Color(0xFF0072B2), Color(0xFFE69F00), Color(0xFF009E73),
        Color(0xFFCC79A7), Color(0xFFD55E00), Color(0xFF56B4E9),
    )
    fun of(i: Int) = palette[i % palette.size]
}

private val dashes = listOf(null, floatArrayOf(18f, 8f), floatArrayOf(4f, 8f), floatArrayOf(18f, 8f, 4f, 8f), null, floatArrayOf(10f, 6f))

@Composable
fun ModelLegend(labels: List<String>, modifier: Modifier = Modifier, colorIdx: List<Int> = labels.indices.toList()) {
    // wraps on narrow screens by using a simple row-of-rows
    val rows = labels.withIndex().chunked(3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                r.forEach { (n, l) ->
                    val i = colorIdx[n]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
                            drawLine(
                                ModelColors.of(i), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                                strokeWidth = 5f, cap = StrokeCap.Butt,
                                pathEffect = dashes[i % dashes.size]?.let { PathEffect.dashPathEffect(it) },
                            )
                        }
                        Text(" $l", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** One line per model over the same hours; a missing value breaks the line (never drawn as zero). */
@Composable
fun ModelLinesChart(
    series: List<List<Double?>>,
    colorIdx: List<Int>,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val all = series.flatMap { it.filterNotNull() }
    if (all.isEmpty()) return
    val lo = if (unit == "mm" || unit == "%") 0.0 else all.min()
    val hi = max(all.max(), lo + if (unit == "mm") 1.0 else 1.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val w = size.width
            val h = size.height
            for (g in 0..3) {
                val y = h * g / 3f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            series.forEachIndexed { si, vals ->
                if (vals.size < 2) return@forEachIndexed
                val path = Path()
                var pen = false
                vals.forEachIndexed { i, v ->
                    if (v == null) { pen = false; return@forEachIndexed }
                    val x = w * i / (vals.size - 1)
                    val y = (h - ((v - lo) / (hi - lo) * h)).toFloat()
                    if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
                }
                drawPath(
                    path, ModelColors.of(colorIdx[si]),
                    style = Stroke(
                        width = 5f, cap = StrokeCap.Round,
                        pathEffect = dashes[colorIdx[si] % dashes.size]?.let { PathEffect.dashPathEffect(it) },
                    ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${Fmt.n1(lo)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${Fmt.n1(hi)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grouped bars: for each day, one bar per model (daily rain mm). Models without a full day leave a gap. */
@Composable
fun ModelRainBars(days: List<ModelAgreement.Day>, modelCount: Int, modifier: Modifier = Modifier) {
    if (days.isEmpty() || modelCount == 0) return
    val maxV = max(days.maxOfOrNull { it.maxMm ?: 0.0 } ?: 0.0, 5.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val groupW = size.width / days.size
            val barW = groupW * 0.8f / modelCount
            drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            days.forEachIndexed { di, d ->
                d.perModel.forEachIndexed { mi, m ->
                    val v = m.rainMm ?: return@forEachIndexed
                    val bh = (v / maxV * (size.height - 6f)).toFloat()
                    if (bh > 0.5f) {
                        drawRect(
                            ModelColors.of(mi),
                            Offset(di * groupW + groupW * 0.1f + mi * barW, size.height - bh),
                            Size(barW * 0.92f, bh),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach {
                Text(
                    it.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        Text("Scale 0–${Fmt.n0(maxV)} mm per day", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
