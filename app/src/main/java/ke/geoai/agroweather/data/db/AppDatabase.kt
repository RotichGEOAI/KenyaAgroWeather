package ke.geoai.agroweather.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import ke.geoai.agroweather.data.CacheRecord
import ke.geoai.agroweather.data.CacheStore
import ke.geoai.agroweather.data.model.Crop
import ke.geoai.agroweather.data.model.FarmProfile
import ke.geoai.agroweather.data.model.SoilType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "farms")
data class FarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val crop: String,
    val soil: String,
    val plantingEpochDay: Long?,
    val probeVwc: Double?,
    val probeAtMs: Long?,
    val alerts: Boolean = true,
)

@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val cacheKey: String,
    val json: String,
    val fetchedAt: Long,
)

@Dao
interface FarmDao {
    @Query("SELECT * FROM farms ORDER BY id")
    fun observeAll(): Flow<List<FarmEntity>>

    @Query("SELECT * FROM farms ORDER BY id")
    suspend fun all(): List<FarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(farm: FarmEntity): Long

    @Query("DELETE FROM farms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE cacheKey = :key")
    suspend fun get(key: String): CacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntity)

    @Query("DELETE FROM cache WHERE cacheKey LIKE 'fc:%' AND fetchedAt < :before")
    suspend fun pruneForecasts(before: Long)
}

@Database(entities = [FarmEntity::class, CacheEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun farmDao(): FarmDao
    abstract fun cacheDao(): CacheDao
}

class RoomCacheStore(private val dao: CacheDao) : CacheStore {
    override suspend fun get(key: String): CacheRecord? =
        dao.get(key)?.let { CacheRecord(it.cacheKey, it.json, it.fetchedAt) }

    override suspend fun put(record: CacheRecord) {
        dao.put(CacheEntity(record.key, record.json, record.fetchedAt))
        if (record.key.startsWith("fc:")) dao.pruneForecasts(record.fetchedAt - 7L * 24L * 3_600_000L)
    }
}

fun FarmEntity.toProfile() = FarmProfile(
    id = id,
    name = name,
    lat = lat,
    lon = lon,
    crop = runCatching { Crop.valueOf(crop) }.getOrDefault(Crop.MAIZE),
    soil = runCatching { SoilType.valueOf(soil) }.getOrDefault(SoilType.LOAM),
    plantingDate = plantingEpochDay?.let { LocalDate.ofEpochDay(it) },
    probeVwc = probeVwc,
    probeAtMs = probeAtMs,
    alertsEnabled = alerts,
)

fun FarmProfile.toEntity() = FarmEntity(
    id = id,
    name = name,
    lat = lat,
    lon = lon,
    crop = crop.name,
    soil = soil.name,
    plantingEpochDay = plantingDate?.toEpochDay(),
    probeVwc = probeVwc,
    probeAtMs = probeAtMs,
    alerts = alertsEnabled,
)
