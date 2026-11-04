package cloudstream.desktop.webview

import android.webkit.WebSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** A plugin WebView's identity must match the engine, or Cloudflare's Turnstile never accepts the solve. */
class WebViewUserAgentTest {
    private val engine = WebSettings.ENGINE_USER_AGENT

    @Test fun `the default is the engine's real user agent, like an Android WebView`() {
        assertEquals(engine, WebSettings().userAgentString)
        assertEquals(JcefRuntime.DEFAULT_USER_AGENT, engine, "keep the shim's copy equal to JcefRuntime's")
    }

    @Test fun `claims the engine cannot back are ignored`() {
        for (ua in listOf(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36", // a plugin's fallback
            engine.replace("Safari", "Mobile Safari"), // a plugin's tweak of the default
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0",
        )) {
            val s = WebSettings().apply { userAgentString = ua }
            assertEquals(engine, s.userAgentString, ua)
        }
    }

    @Test fun `the engine reports the OS it runs on, and another OS's claim is refused`() {
        val os = System.getProperty("os.name").lowercase()
        val expected = when { os.startsWith("windows") -> "Windows NT"; os.contains("mac") -> "Mac OS X"; else -> "X11; Linux" }
        kotlin.test.assertTrue(expected in engine, engine)
        // Same Chrome version, other OS (Windows on Linux or the other way round): Cloudflare would catch it.
        val other = engine.replace(WebSettings.osToken(), if ("Windows" in engine) "X11; Linux x86_64" else "Windows NT 10.0; Win64; x64")
        assertEquals(engine, WebSettings().apply { userAgentString = other }.userAgentString)
    }

    @Test fun `a consistent custom user agent is kept, null restores the default`() {
        val same = engine.replace("143.0.0.0", "143.0.7499.193")
        val s = WebSettings().apply { userAgentString = same }
        assertEquals(same, s.userAgentString)
        s.userAgentString = null
        assertEquals(engine, s.userAgentString)
    }
}
