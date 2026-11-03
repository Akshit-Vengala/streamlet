package cloudstream.desktop.webview

import cloudstream.desktop.contracts.Clearance
import cloudstream.desktop.contracts.HeadlessBrowser
import cloudstream.desktop.contracts.ResolveResult
import com.lagradost.cloudstream3.app
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide entry point for the desktop web layer.
 *
 * Typical app start-up:
 * ```
 * if (!DesktopWebView.isInstalled()) DesktopWebView.install { p -> showProgress(p) }  // explicit, can take minutes
 * val browser: HeadlessBrowser = DesktopWebView.headlessBrowser()
 * ```
 * If [install] is never called, CEF is installed silently on first use.
 */
object DesktopWebView {
    @Volatile
    private var override: WebEngine? = null
    private val jcef: JcefEngine by lazy { JcefEngine() }

    /** The active backend. Tests (or an alternative backend) can swap it with [useEngine]. */
    val engine: WebEngine get() = override ?: jcef

    fun useEngine(engine: WebEngine?) {
        override = engine
    }

    fun isInstalled(): Boolean = JcefRuntime.isInstalled()

    /** Explicit first-run step: download + extract + initialize CEF, reporting progress. Blocking. */
    fun install(onProgress: (JcefProgress) -> Unit = {}) {
        JcefRuntime.install(onProgress)
    }

    fun headlessBrowser(): HeadlessBrowser = EngineHeadlessBrowser { engine }

    /**
     * Push cookies solved in the browser into NiceHttp's cookie jar (see [SharedCookieJar]) so that
     * subsequent `app.get(...)` calls to [url] carry them.
     */
    fun shareCookies(url: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        SharedCookieJar.install(app)
        SharedCookieJar.put(url, cookies)
    }
}

/**
 * Host-keyed in-memory OkHttp [CookieJar]. NiceHttp's `Requests.baseClient` defaults to no jar at all,
 * so [install] swaps in this one (only when the client has no custom jar). Browser cookies pushed
 * via [put] are then sent automatically to the matching host.
 */
object SharedCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { m[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) } ?: emptyList()
    }

    fun put(url: String, cookies: Map<String, String>) {
        val http = url.toHttpUrlOrNull() ?: return
        val built = cookies.mapNotNull { (k, v) ->
            runCatching {
                Cookie.Builder().name(k).value(v).hostOnlyDomain(http.host).path("/").build()
            }.getOrNull()
        }
        saveFromResponse(http, built)
    }

    fun clear() = store.clear()

    /** Installs this jar into [requests]'s client unless it already has a real jar. Idempotent. */
    fun install(requests: com.lagradost.nicehttp.Requests) {
        val client = requests.baseClient
        if (client.cookieJar === this) return
        if (client.cookieJar !== CookieJar.NO_COOKIES) return
        requests.baseClient = client.newBuilder().cookieJar(this).build()
    }
}

/** Static-resource filter copied from the Android WebViewResolver: skip things we never need. */
internal object StaticBlocklist {
    private val files = listOf(
        ".jpg", ".png", ".webp", ".mpg", ".mpeg", ".jpeg", ".webm", ".mp4", ".mp3", ".gifv", ".flv",
        ".asf", ".mov", ".mng", ".mkv", ".ogg", ".avi", ".wav", ".woff2", ".woff", ".ttf", ".css",
        ".vtt", ".srt", ".ts", ".gif",
    )

    fun shouldBlock(url: String): Boolean {
        if (url.startsWith("wss://") || url.startsWith("ws://")) return true
        if (url.contains("recaptcha") || url.contains("/cdn-cgi/")) return false
        if (url.endsWith("/favicon.ico")) return true
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
        return files.any { path.contains(it) }
    }
}

/** Solves Cloudflare challenges with an [engine]; shared by [HeadlessBrowser.clearance] and CloudflareKiller. */
internal object CloudflareSolver {
    /**
     * Headless, like upstream's CloudflareKiller: the browser is never shown; without clearance within [timeoutMs]
     * the request simply proceeds without it. ([showAfterMs] exists for tests only.)
     */
    suspend fun solve(engine: WebEngine, url: String, timeoutMs: Long, showAfterMs: Long? = null): Clearance? {
        var found: Map<String, String>? = null
        runBrowseSession(
            engine, BrowseSpec(url = url), timeoutMs, showAfterMs = showAfterMs,
            poll = {
                val c = runCatching { engine.cookies(url) }.getOrDefault(emptyMap())
                if (c.containsKey("cf_clearance")) { found = c; true } else false
            },
        ) { Verdict.CONTINUE }
        val cookies = found ?: return null
        DesktopWebView.shareCookies(url, cookies)
        return Clearance(cookies, engine.userAgent)
    }
}

/** [HeadlessBrowser] contract implementation on top of any [WebEngine]. */
class EngineHeadlessBrowser(private val engineProvider: () -> WebEngine) : HeadlessBrowser {
    constructor(engine: WebEngine) : this({ engine })

    override suspend fun resolve(
        url: String, interceptRegex: Regex, headers: Map<String, String>, timeoutMs: Long,
    ): ResolveResult {
        val engine = engineProvider()
        var matched: String? = null
        runBrowseSession(engine, BrowseSpec(url = url, headers = headers), timeoutMs) { req ->
            when {
                interceptRegex.containsMatchIn(req.url) -> { matched = req.url; Verdict.STOP }
                StaticBlocklist.shouldBlock(req.url) -> Verdict.BLOCK
                else -> Verdict.CONTINUE
            }
        }
        val cookies = runCatching { engine.cookies(matched ?: url) }.getOrDefault(emptyMap())
        DesktopWebView.shareCookies(matched ?: url, cookies)
        return ResolveResult(matched, cookies, engine.userAgent)
    }

    override suspend fun clearance(url: String, timeoutMs: Long): Clearance? =
        CloudflareSolver.solve(engineProvider(), url, timeoutMs)
}
