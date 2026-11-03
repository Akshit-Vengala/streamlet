package cloudstream.desktop.player

import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.SubtitleSource
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvOptionsTest {
    private fun src(headers: Map<String, String> = emptyMap(), referer: String? = null, start: Long = 0) =
        PlaybackSource("https://h/x.m3u8", headers, referer, true, false,
            listOf(SubtitleSource("https://h/a.vtt", "en", emptyMap())), start)

    @Test fun mapsRefererUaAndHeaders() {
        val c = MpvOptions.map(src(mapOf("User-Agent" to "UA/1", "Origin" to "https://o", "X-A" to "b,c"), "https://ref/"))
        assertEquals("https://ref/", c.options["referrer"])
        assertEquals("UA/1", c.options["user-agent"])
        assertEquals("%17%Origin: https://o,%8%X-A: b,c", c.options["http-header-fields"])
        assertEquals("0", c.options["start"])
        assertEquals(1, c.subtitles.size)
    }

    @Test fun refererHeaderFallbackAndStart() {
        val c = MpvOptions.map(src(mapOf("referer" to "https://r/"), null, 90_500))
        assertEquals("https://r/", c.options["referrer"])
        assertEquals("90.500", c.options["start"])
        assertEquals("", c.options["http-header-fields"])
        assertEquals(MpvOptions.DEFAULT_UA, c.options["user-agent"])
    }

    @Test fun commandLine() {
        val args = MpvOptions.map(src(referer = "https://r/")).toCommandLine()
        assertTrue("--referrer=https://r/" in args)
        assertTrue("--sub-file=https://h/a.vtt" in args)
        assertEquals("https://h/x.m3u8", args.last())
    }

    @Test fun fixtureEnforcesReferer() {
        ReferrerFixtureServer("https://ref/").use { s ->
            fun code(ref: String?): Int {
                val c = URL("${s.baseUrl}/sub.vtt").openConnection() as HttpURLConnection
                ref?.let { c.setRequestProperty("Referer", it) }
                return c.responseCode
            }
            assertEquals(403, code(null))
            assertEquals(403, code("https://wrong/"))
            assertEquals(200, code("https://ref/"))
        }
    }
}
