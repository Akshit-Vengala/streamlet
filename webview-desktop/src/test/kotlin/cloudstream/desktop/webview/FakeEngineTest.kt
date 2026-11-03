package cloudstream.desktop.webview

import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted engine: replays [requests] to the listener, honouring STOP. No CEF needed. */
class FakeEngine(
    private val requests: List<CapturedRequest> = emptyList(),
    var cookieMap: Map<String, String> = emptyMap(),
    /** cookies become visible to [cookies] only after this many polls (simulates a solving challenge). */
    private val cookiesAfterPolls: Int = 0,
) : WebEngine {
    override val userAgent = "FakeUA/1.0"
    val opened = mutableListOf<BrowseSpec>()

    @Volatile var shown = false
    @Volatile var closed = false
    private var polls = 0

    override fun open(spec: BrowseSpec, onRequest: (CapturedRequest) -> Verdict): BrowseHandle {
        opened += spec
        Thread {
            for (r in requests) if (onRequest(r) == Verdict.STOP) break
        }.start()
        return object : BrowseHandle {
            override fun setVisible(visible: Boolean) { shown = visible }
            override fun close() { closed = true }
        }
    }

    override suspend fun cookies(url: String): Map<String, String> =
        if (polls++ >= cookiesAfterPolls) cookieMap else emptyMap()

    override suspend fun clearCookies() {}
}

class FakeEngineTest {
    @AfterTest fun reset() { DesktopWebView.useEngine(null) }

    private fun req(url: String) = CapturedRequest(url, "GET", mapOf("Accept" to "*/*"))

    @Test fun headlessResolveReturnsFirstMatchAndCookies() = runBlocking {
        val fake = FakeEngine(
            listOf(req("https://a.test/page"), req("https://a.test/logo.png"), req("https://cdn.test/v/index.m3u8"), req("https://x.test/later.m3u8")),
            cookieMap = mapOf("sid" to "1"),
        )
        val r = EngineHeadlessBrowser(fake).resolve("https://a.test/page", Regex("""\.m3u8"""), emptyMap(), 5_000)
        assertEquals("https://cdn.test/v/index.m3u8", r.matchedUrl)
        assertEquals(mapOf("sid" to "1"), r.cookies)
        assertEquals("FakeUA/1.0", r.userAgent)
        assertTrue(fake.closed)
    }

    @Test fun headlessResolveTimesOutWithNullMatch() = runBlocking {
        val fake = FakeEngine(listOf(req("https://a.test/page")))
        val r = EngineHeadlessBrowser(fake).resolve("https://a.test/page", Regex("nomatch"), emptyMap(), 600)
        assertNull(r.matchedUrl)
        assertTrue(fake.closed)
    }

    @Test fun clearanceWaitsForCfClearanceCookie() = runBlocking {
        val fake = FakeEngine(cookieMap = mapOf("cf_clearance" to "tok"), cookiesAfterPolls = 3)
        val c = EngineHeadlessBrowser(fake).clearance("https://cf.test/", 10_000)
        assertNotNull(c)
        assertEquals("tok", c.cookies["cf_clearance"])
        assertEquals("FakeUA/1.0", c.userAgent)
    }

    @Test fun clearanceRevealsWindowWhenStuckThenTimesOut() = runBlocking {
        val fake = FakeEngine()
        val c = CloudflareSolver.solve(fake, "https://cf.test/", 1_500, showAfterMs = 300)
        assertNull(c)
        assertTrue(fake.shown)
    }

    @Test fun webViewResolverMatchesInterceptAndAdditionalUrls() = runBlocking {
        val fake = FakeEngine(
            listOf(req("https://a.test/x.png"), req("https://a.test/sub.vtt"), req("https://a.test/embed"), req("https://a.test/video.mp4?x"), req("https://a.test/end")),
        )
        DesktopWebView.useEngine(fake)
        val (fixed, extra) = WebViewResolver(Regex("video\\.mp4"), additionalUrls = listOf(Regex("\\.vtt|/embed")))
            .resolveUsingWebView("https://a.test/start")
        assertEquals("https://a.test/video.mp4?x", fixed?.url?.toString())
        assertEquals(listOf("https://a.test/sub.vtt", "https://a.test/embed"), extra.map { it.url.toString() })
        assertEquals("FakeUA/1.0", WebViewResolver.webViewUserAgent)
    }

    @Test fun sharedCookieJarFeedsNiceHttpClient() {
        DesktopWebView.shareCookies("https://cf.test/page", mapOf("cf_clearance" to "tok"))
        val jar = com.lagradost.cloudstream3.app.baseClient.cookieJar
        val cookies = jar.loadForRequest(okhttp3.HttpUrl.Builder().scheme("https").host("cf.test").build())
        assertEquals(listOf("cf_clearance"), cookies.map { it.name })
    }

    @Test fun webViewResolverShadowsLibraryClass() {
        // The class resolved at runtime must be ours (webview-desktop output dir), not library-jvm's no-op.
        val loc = WebViewResolver::class.java.protectionDomain.codeSource.location.toString()
        assertTrue("library-jvm" !in loc, "WebViewResolver resolved from $loc")
    }
}
