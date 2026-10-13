package ke.geoai.agroweather.data

import android.content.Context
import java.util.concurrent.TimeUnit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("agro_prefs", Context.MODE_PRIVATE)

    var selectedFarmId: Long
        get() = sp.getLong(KEY_FARM, -1L)
        set(v) = sp.edit().putLong(KEY_FARM, v).apply()

    var alertsEnabled: Boolean
        get() = sp.getBoolean(KEY_ALERTS, true)
        set(v) = sp.edit().putBoolean(KEY_ALERTS, v).apply()

    /**
     * Returns true the first time [key] is seen (caller should notify), false for repeats.
     * Entries are stored as "<epochDay>|<key>" and pruned after 7 days.
     */
    @Synchronized
    fun markNotified(key: String): Boolean {
        val today = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        val current = HashSet(sp.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet())
        current.removeAll { (it.substringBefore('|').toLongOrNull() ?: 0L) < today - 7 }
        val fresh = current.none { it.substringAfter('|') == key }
        if (fresh) current += "$today|$key"
        sp.edit().putStringSet(KEY_NOTIFIED, current).apply()
        return fresh
    }

    private companion object {
        const val KEY_FARM = "selected_farm"
        const val KEY_ALERTS = "alerts_enabled"
        const val KEY_NOTIFIED = "notified"
    }
}
