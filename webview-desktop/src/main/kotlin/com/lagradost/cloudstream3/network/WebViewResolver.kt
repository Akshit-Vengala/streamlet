package com.lagradost.cloudstream3.network

// DESKTOP OVERRIDE of upstream library's `WebViewResolver.jvm.kt` (a no-op passthrough).
// Same FQN and public signatures as the `expect class` in library/commonMain; this jar must come
// BEFORE library-jvm on the classpath. See patches/README (webview-* entries).

import cloudstream.desktop.webview.BrowseSpec
import cloudstream.desktop.webview.CapturedRequest
import cloudstream.desktop.webview.DesktopWebView
import cloudstream.desktop.webview.StaticBlocklist
import cloudstream.desktop.webview.Verdict
import cloudstream.desktop.webview.runBrowseSession
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CopyOnWriteArrayList

/**
 * When used as Interceptor additionalUrls cannot be returned, use WebViewResolver(...).resolveUsingWebView(...)
 * @param interceptUrl will stop the WebView when reaching this url.
 * @param additionalUrls this will make resolveUsingWebView also return all other requests matching the list of Regex.
 * @param userAgent if null then will use the default user agent
 * @param useOkhttp accepted for source compatibility; CEF always uses its own network stack on desktop.
 * @param script pass custom js to execute
 * @param scriptCallback will be called with the result from custom js
 * @param timeout close webview after timeout
 * */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = DEFAULT_TIMEOUT
) : Interceptor {

    companion object {
        val DEFAULT_TIMEOUT: Long = 60_000L
        var webViewUserAgent: String? = null

        @JvmName("getWebViewUserAgent1")
        fun getWebViewUserAgent(): String? {
            return webViewUserAgent ?: safe {
                DesktopWebView.engine.userAgent.also { webViewUserAgent = it }
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveUsingWebView(request).first
            return@runBlocking chain.proceed(fixedRequest ?: request)
        }
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        return try {
            resolveUsingWebView(
                requestCreator(method, url, referer = referer, headers = headers), requestCallBack
            )
        } catch (e: java.lang.IllegalArgumentException) {
            logError(e)
            debugException { "ILLEGAL URL IN resolveUsingWebView!" }
            return null to emptyList()
        }
    }

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false }
    ): Pair<Request?, List<Request>> {
        val engine = DesktopWebView.engine
        val url = request.url.toString()
        var fixedRequest: Request? = null
        val extraRequestList = CopyOnWriteArrayList<Request>()

        val spec = BrowseSpec(
            url = url,
            method = request.method,
            headers = request.headers.toMap(),
            userAgent = userAgent,
            script = script,
            onScriptResult = scriptCallback,
        )

        runBrowseSession(engine, spec, timeout) { req ->
            when {
                interceptUrl.containsMatchIn(req.url) -> {
                    fixedRequest = req.toRequest()?.also { requestCallBack(it) }
                    Verdict.STOP
                }

                additionalUrls.any { it.containsMatchIn(req.url) } -> {
                    val r = req.toRequest()
                    if (r != null) extraRequestList.add(r)
                    if (r != null && requestCallBack(r)) Verdict.STOP else Verdict.CONTINUE
                }

                StaticBlocklist.shouldBlock(req.url) -> Verdict.BLOCK
                else -> Verdict.CONTINUE
            }
        }

        // Same as Android: remember the real browser UA so CloudflareKiller & co can reuse it.
        webViewUserAgent = engine.userAgent
        runCatching { DesktopWebView.shareCookies(url, engine.cookies(url)) }
        return fixedRequest to extraRequestList
    }

    private fun CapturedRequest.toRequest(): Request? =
        safe { requestCreator(method, url, headers) }
}
