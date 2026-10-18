package ke.geoai.agroweather.data.net

/** Minimal HTTP seam so the repository can be unit-tested without a network. */
fun interface HttpFetcher {
    suspend fun get(url: String): String

    /**
     * GET with HTTP Basic credentials, sent in the Authorization header (never in the URL).
     * Fakes that do not care about auth can ignore the credentials.
     */
    suspend fun getBasic(url: String, user: String, password: String): String = get(url)
}
