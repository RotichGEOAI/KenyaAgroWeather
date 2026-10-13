package ke.geoai.agroweather

import ke.geoai.agroweather.data.CacheRecord
import ke.geoai.agroweather.data.CacheStore
import ke.geoai.agroweather.data.WeatherRepository
import ke.geoai.agroweather.data.model.ApiException
import ke.geoai.agroweather.data.net.HttpFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class MemCache : CacheStore {
    val map = HashMap<String, CacheRecord>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(record: CacheRecord) { map[record.key] = record }
}

private class FakeHttp(var body: String? = null, var error: Exception? = null) : HttpFetcher {
    var calls = 0
    override suspend fun get(url: String): String {
        calls++
        error?.let { throw it }
        return body!!
    }
}

class RepositoryTest {
    private val enso = "JAS 2026 29.12 2.16"
    private var now = 1_000_000L

    private fun repo(http: HttpFetcher, cache: CacheStore) = WeatherRepository(http, cache) { now }

    @Test fun freshCacheAvoidsNetwork() = runBlocking {
        val http = FakeHttp(enso); val r = repo(http, MemCache())
        r.enso(); now += 60_000; val second = r.enso()
        assertEquals(1, http.calls)
        assertFalse(second.stale)
    }

    @Test fun forceBypassesCache() = runBlocking {
        val http = FakeHttp(enso); val r = repo(http, MemCache())
        r.enso(); r.enso(force = true)
        assertEquals(2, http.calls)
    }

    @Test fun expiredCacheRefetches() = runBlocking {
        val http = FakeHttp(enso); val r = repo(http, MemCache())
        r.enso(); now += WeatherRepository.ENSO_TTL + 1; r.enso()
        assertEquals(2, http.calls)
    }

    @Test fun networkFailureServesStaleCopy() = runBlocking {
        val http = FakeHttp(enso); val r = repo(http, MemCache())
        r.enso()
        now += WeatherRepository.ENSO_TTL + 1
        http.error = IOException("offline")
        val l = r.enso()
        assertTrue(l.stale)
        assertEquals(2.16, l.data.last().anomaly, 1e-9)
    }

    @Test fun failureWithoutCacheThrows() = runBlocking {
        val r = repo(FakeHttp(error = IOException("offline")), MemCache())
        try { r.enso(); throw AssertionError("expected IOException") } catch (e: IOException) { }
    }

    @Test fun badBodyIsNeverCached() = runBlocking {
        val cache = MemCache(); val http = FakeHttp("<html>garbage</html>")
        val r = repo(http, cache)
        try { r.enso(); throw AssertionError("expected ApiException") } catch (e: ApiException) { }
        assertNull(cache.get("enso"))
    }

    @Test fun badBodyFallsBackToLastGoodCopy() = runBlocking {
        val http = FakeHttp(enso); val r = repo(http, MemCache())
        r.enso()
        now += WeatherRepository.ENSO_TTL + 1
        http.body = "<html>maintenance</html>"
        val l = r.enso()
        assertTrue(l.stale)
    }

    @Test fun shortPlaceQueriesSkipNetwork() = runBlocking {
        val http = FakeHttp("{}"); val r = repo(http, MemCache())
        assertTrue(r.searchPlaces("a").isEmpty())
        assertEquals(0, http.calls)
    }
}
