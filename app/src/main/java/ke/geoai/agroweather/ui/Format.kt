package ke.geoai.agroweather.ui

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    val dayHm: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    val month: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun n0(v: Double): String = v.roundToInt().toString()
    fun n1(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun signed1(v: Double): String = String.format(Locale.US, "%+.1f", v)
    fun coord(v: Double): String = String.format(Locale.US, "%.5f", v)
    /** Accepts "0,5" as well as "0.5" (many keyboards offer only a comma). */
    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
    fun signed0(v: Double): String = String.format(Locale.US, "%+.0f", v)

    fun ago(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val min = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 60 * 24 -> "${min / 60} h ago"
            else -> "${min / (60 * 24)} d ago"
        }
    }
}
