package ke.geoai.agroweather.data

data class CacheRecord(val key: String, val json: String, val fetchedAt: Long)

interface CacheStore {
    suspend fun get(key: String): CacheRecord?
    suspend fun put(record: CacheRecord)
}
