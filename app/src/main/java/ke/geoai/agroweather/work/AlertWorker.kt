package ke.geoai.agroweather.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ke.geoai.agroweather.AgroWeatherApp
import ke.geoai.agroweather.data.db.toProfile
import ke.geoai.agroweather.domain.Analyzer
import ke.geoai.agroweather.domain.Severity
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Runs every ~3 h: re-evaluates each farm and posts a notification once per new alert. */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as AgroWeatherApp).container
        if (!c.prefs.alertsEnabled) return Result.success()

        val farms = c.farms.all().filter { it.alerts }.take(MAX_FARMS)
        if (farms.isEmpty()) return Result.success()

        var failures = 0
        var sent = 0
        for (entity in farms) {
            try {
                val farm = entity.toProfile()
                val fc = c.repo.forecast(farm.lat, farm.lon).data
                val analysis = Analyzer.analyze(farm, fc, null, fc.localNow())
                for (alert in analysis.alerts) {
                    if (sent >= MAX_PER_RUN) break
                    if (c.prefs.markNotified("${farm.id}:${alert.id}")) {
                        val prefix = if (alert.severity == Severity.WARNING) "⚠ " else ""
                        Notifier.show(
                            applicationContext, "${farm.id}:${alert.id}".hashCode(),
                            "$prefix${alert.title} – ${farm.name}", alert.message,
                        )
                        sent++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
            }
        }
        return if (failures == farms.size) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_FARMS = 5
        const val MAX_PER_RUN = 4
    }
}

object AlertScheduler {
    private const val NAME = "agro_alerts"

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
