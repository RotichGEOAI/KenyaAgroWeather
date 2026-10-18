package ke.geoai.agroweather.data.net

import ke.geoai.agroweather.data.model.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp-backed fetcher: 3 attempts with back-off for network errors / 5xx / 429; 4xx fails fast. */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) : HttpFetcher {

    override suspend fun get(url: String): String = fetch(url, null)

    override suspend fun getBasic(url: String, user: String, password: String): String =
        fetch(url, okhttp3.Credentials.basic(user, password))

    private suspend fun fetch(url: String, authorization: String?): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply { if (authorization != null) header("Authorization", authorization) }
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return@withContext resp.body?.string() ?: throw IOException("Empty response")
                    }
                    if (resp.code in 400..499 && resp.code != 429) {
                        throw ApiException("Service returned HTTP ${resp.code}")
                    }
                    last = IOException("HTTP ${resp.code}")
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(600L * (attempt + 1))
        }
        throw last ?: IOException("Network error")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        /** MET Norway blocks generic agents and asks for a unique one with contact info: put your repo URL or email here. */
        const val USER_AGENT = "KenyaAgroWeather/1.0 (github.com/your-account/KenyaAgroWeather)"
    }
}
