package cloudstream.desktop.loader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal HTTP seam so tests (and callers) can substitute the network. */
interface HttpFetcher {
    suspend fun getBytes(url: String): ByteArray

    /** Returns the `Location` header of a non-followed request, or null. Used for shortcode URLs. */
    suspend fun redirectLocation(url: String): String?
}

class JdkHttpFetcher(
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CloudStreamDesktop",
    private val timeout: Duration = Duration.ofSeconds(60),
) : HttpFetcher {
    private val follow = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val noFollow = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun req(url: String) = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", userAgent).timeout(timeout).GET().build()

    override suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val res = follow.send(req(url), HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() !in 200..299) throw IOException("HTTP ${res.statusCode()} for $url")
        res.body()
    }

    override suspend fun redirectLocation(url: String): String? = withContext(Dispatchers.IO) {
        val res = noFollow.send(req(url), HttpResponse.BodyHandlers.discarding())
        res.headers().firstValue("Location").orElse(null)
    }
}
