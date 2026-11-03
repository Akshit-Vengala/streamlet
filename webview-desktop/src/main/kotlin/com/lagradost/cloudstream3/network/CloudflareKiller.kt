package com.lagradost.cloudstream3.network

// DESKTOP port of app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt.
// Upstream's lives in the Android-only `app` module, so this simply provides the same FQN and API.
// Android's CookieManager is replaced by the JCEF cookie store; the WebViewResolver round-trip is
// replaced by CloudflareSolver (poll for cf_clearance, show a window if a human is needed).

import cloudstream.desktop.webview.CloudflareSolver
import cloudstream.desktop.webview.DesktopWebView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.nicehttp.Requests.Companion.await
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        const val SOLVE_TIMEOUT_MS = 120_000L

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    // Android clears WebView cookies on init so a fresh clearance is generated; we do it lazily on
    // the first solve so merely constructing this object never boots CEF.
    @Volatile
    private var cleared = false

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Gets the headers with cookies, webview user agent included! */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return buildHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }

            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val existing = request.header("Cookie")?.let(::parseCookieMap) ?: emptyMap()
        val headers = buildHeaders(request.headers.toMap() + userAgentMap, cookies + existing)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val engine = DesktopWebView.engine
        val url = request.url.toString()
        Log.d(TAG, "Loading browser to solve cloudflare for $url")

        if (!cleared) {
            cleared = true
            runCatching { engine.clearCookies() }
        }
        val clearance = CloudflareSolver.solve(engine, url, SOLVE_TIMEOUT_MS) ?: return null
        WebViewResolver.webViewUserAgent = clearance.userAgent
        savedCookies[request.url.host] = clearance.cookies
        return proceed(request, clearance.cookies)
    }

    private fun buildHeaders(base: Map<String, String>, cookies: Map<String, String>): Headers {
        val b = Headers.Builder()
        // case-insensitive de-dupe, later wins; drop any Cookie header (merged below)
        base.entries.filter { !it.key.equals("cookie", true) }.forEach { b.set(it.key, it.value) }
        if (cookies.isNotEmpty()) b.set("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return b.build()
    }
}
