package ke.geoai.agroweather.domain

import ke.geoai.agroweather.data.model.Climatology
import ke.geoai.agroweather.data.model.MonthOutlook
import ke.geoai.agroweather.data.model.SeasonalDay
import java.time.YearMonth

/** Aggregates daily seasonal-ensemble output into monthly figures and anomalies against a baseline. */
object MonthlyOutlookBuilder {
    private const val MIN_DAYS = 7

    fun build(days: List<SeasonalDay>, clim: Climatology?): List<MonthOutlook> =
        days.groupBy { YearMonth.from(it.date) }
            .toSortedMap()
            .mapNotNull { (ym, list) ->
                val precipDays = list.filter { it.precipMm != null }
                if (precipDays.size < MIN_DAYS) return@mapNotNull null

                val means = list.mapNotNull { d ->
                    val hi = d.tMaxC
                    val lo = d.tMinC
                    if (hi != null && lo != null) (hi + lo) / 2 else null
                }
                if (means.isEmpty()) return@mapNotNull null
                val tMean = means.average()

                val covered = precipDays.size
                val inMonth = ym.lengthOfMonth()
                val sum = precipDays.sumOf { it.precipMm!! }
                val estimated = if (covered >= inMonth) sum else sum * inMonth / covered

                var pAnom: Double? = null
                var tAnom: Double? = null
                if (clim != null) {
                    val expectedCovered = clim.precipMonthMm[ym.monthValue - 1] / inMonth * covered
                    if (expectedCovered >= 5.0) pAnom = (sum / expectedCovered - 1.0) * 100.0
                    tAnom = tMean - clim.temp(ym.monthValue)
                }
                MonthOutlook(ym, tMean, estimated, tAnom, pAnom, covered, partial = covered < inMonth)
            }
}
