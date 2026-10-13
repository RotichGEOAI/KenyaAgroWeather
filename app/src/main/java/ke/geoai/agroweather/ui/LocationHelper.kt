package ke.geoai.agroweather.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float)

object LocationHelper {
    const val TARGET_ACCURACY_M = 10f

    fun hasAnyPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh high-accuracy GPS fix (waits up to 20 s). Returns null if none could be obtained. */
    suspend fun fix(ctx: Context): GpsFix? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(20_000)
            .setMaxUpdateAgeMillis(0)
            .build()
        val loc = LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(request, null).await()
            ?: return null
        return GpsFix(loc.latitude, loc.longitude, loc.accuracy)
    }

    fun describe(accuracyM: Float): String {
        val m = accuracyM.roundToInt()
        return if (accuracyM <= TARGET_ACCURACY_M) "GPS fix ±$m m – within 10 m."
        else "GPS fix ±$m m – coarser than 10 m. Step into the open and retry, or tap the map to place the exact spot."
    }
}
