package ke.geoai.agroweather

import android.app.Application
import android.content.Context
import androidx.room.Room
import ke.geoai.agroweather.data.Prefs
import ke.geoai.agroweather.data.WeatherRepository
import ke.geoai.agroweather.data.db.AppDatabase
import ke.geoai.agroweather.data.db.RoomCacheStore
import ke.geoai.agroweather.data.net.OkHttpFetcher
import ke.geoai.agroweather.work.AlertScheduler
import ke.geoai.agroweather.work.Notifier
import org.osmdroid.config.Configuration
import java.io.File

/** Manual dependency container – small enough that a DI framework would be overkill. */
class AppContainer(context: Context) {
    private val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "agroweather.db").build()
    val farms = db.farmDao()
    val repo = WeatherRepository(OkHttpFetcher(), RoomCacheStore(db.cacheDao()))
    val prefs = Prefs(context)
}

class AgroWeatherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // osmdroid: private cache dir (no storage permission) and an identifying user agent
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        Notifier.createChannel(this)
        AlertScheduler.schedule(this)
    }
}
