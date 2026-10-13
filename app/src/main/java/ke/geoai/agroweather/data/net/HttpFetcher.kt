package ke.geoai.agroweather.data.net

/** Minimal HTTP seam so the repository can be unit-tested without a network. */
fun interface HttpFetcher {
    suspend fun get(url: String): String
}
