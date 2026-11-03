package cloudstream.desktop.app.sync

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal HTTP + JSON helper for the tracking / subtitle services (no plugin code involved). */
object Http {
    val gson = Gson()
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    const val UA = "CloudStreamDesktop/1.0"

    class Response(val code: Int, val body: ByteArray, val finalUrl: String) {
        val text: String get() = body.toString(Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
        fun json(): JsonElement = runCatching { JsonParser.parseString(text) }.getOrElse { JsonNull.INSTANCE }
        fun obj(): JsonObject = json().let { if (it.isJsonObject) it.asJsonObject else JsonObject() }
    }

    class HttpError(val code: Int, body: String) : Exception("HTTP $code: ${body.take(300)}")

    fun form(vararg kv: Pair<String, Any?>): String =
        kv.filter { it.second != null }.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v.toString()) }

    fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    suspend fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null,
        contentType: String? = null, timeoutSec: Long = 30,
    ): Response = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeoutSec)).header("User-Agent", UA)
        headers.forEach { (k, v) -> if (!k.equals("user-agent", true)) b.header(k, v) else b.setHeader("User-Agent", v) }
        if (contentType != null) b.header("Content-Type", contentType)
        val pub = if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        b.method(method, pub)
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        Response(r.statusCode(), r.body() ?: ByteArray(0), r.uri().toString())
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = request("GET", url, headers)
    suspend fun postJson(url: String, json: Any, headers: Map<String, String> = emptyMap(), method: String = "POST") =
        request(method, url, headers, if (json is String) json else gson.toJson(json), "application/json")
    suspend fun postForm(url: String, form: String, headers: Map<String, String> = emptyMap(), method: String = "POST") =
        request(method, url, headers, form, "application/x-www-form-urlencoded")

    fun Response.orThrow(): Response { if (!ok) throw HttpError(code, text); return this }
}

// Null-safe JSON accessors.
fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
fun JsonElement?.a(key: String) = (this as? JsonObject)?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
fun JsonElement?.s(key: String): String? = (this as? JsonObject)?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonElement?.i(key: String): Int? = runCatching { (this as? JsonObject)?.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt() }.getOrNull()
fun JsonElement?.d(key: String): Double? = runCatching { (this as? JsonObject)?.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble }.getOrNull()
