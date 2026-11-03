package cloudstream.desktop.webview

import com.lagradost.cloudstream3.network.WebViewResolver
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-CEF tests. Opt-in (downloads ~200 MB on first run):
 *   ./gradlew :webview-desktop:test -Pjcef --tests '*JcefIntegrationTest*'
 * Optional: -Dcloudstream.jcef.cfurl=https://some-cloudflare-site/ is not wired through Gradle;
 * use the main() in JcefSmoke for ad-hoc Cloudflare checks.
 */
@EnabledIfSystemProperty(named = "cloudstream.jcef.tests", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JcefIntegrationTest {
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeAll fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { ex ->
            val html = """<html><body>hi
                <script>
                  document.cookie = "cf_clearance=solved123; path=/";
                  setTimeout(function(){ fetch('/media/index.m3u8?tok=1'); }, 300);
                </script></body></html>"""
            ex.responseHeaders.add("Set-Cookie", "srv=abc; Path=/")
            ex.responseHeaders.add("Content-Type", "text/html")
            val b = html.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/media") { ex -> ex.sendResponseHeaders(200, 0); ex.responseBody.close() }
        server.start()

        val t0 = System.currentTimeMillis()
        DesktopWebView.install { println("[jcef] ${it.stage} ${it.fraction ?: ""} (+${System.currentTimeMillis() - t0}ms)") }
    }

    @AfterAll fun tearDown() { server.stop(0); DesktopWebView.useEngine(null) }

    @Test fun resolveInterceptsRequestAndReturnsCookies() = runBlocking {
        val r = DesktopWebView.headlessBrowser().resolve("$base/page", Regex("""\.m3u8"""), emptyMap(), 30_000)
        println("resolve -> $r")
        assertNotNull(r.matchedUrl)
        assertTrue(r.matchedUrl!!.endsWith("/media/index.m3u8?tok=1"))
        assertEquals("abc", r.cookies["srv"])
        assertTrue(r.userAgent.contains("Chrome/"))
    }

    @Test fun clearanceDetectsCfClearanceCookie() = runBlocking {
        val c = DesktopWebView.headlessBrowser().clearance("$base/page", 30_000)
        println("clearance -> $c")
        assertNotNull(c)
        assertEquals("solved123", c.cookies["cf_clearance"])
    }

    @Test fun webViewResolverDropIn() = runBlocking {
        var scriptResult: String? = null
        val (fixed, extra) = WebViewResolver(
            Regex("""\.m3u8"""), additionalUrls = listOf(Regex("/page")),
            script = "document.title = 'x'; 1 + 1", scriptCallback = { scriptResult = it },
        ).resolveUsingWebView("$base/page")
        println("resolver -> $fixed / $extra / script=$scriptResult")
        assertNotNull(fixed)
        assertTrue(extra.any { it.url.encodedPath == "/page" })
        assertEquals("2", scriptResult)
    }
}
