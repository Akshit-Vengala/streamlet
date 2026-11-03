package cloudstream.desktop.player

import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.SubtitleSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in: -Dcloudstream.mpv.tests=true, libmpv-2.dll locatable, and CLOUDSTREAM_TEST_HLS_DIR pointing at a dir with
 * index.m3u8 + segments (generate with ffmpeg; see ReferrerFixtureServer). Runs headless (vo=null is
 * not set; a window may appear because no wid is attached).
 */
class LibMpvIntegrationTest {
    @Test fun hlsWithRefererAndVtt() {
        assumeTrue(System.getProperty("cloudstream.mpv.tests") == "true")
        assumeTrue(MpvLocator.findDll() != null)
        val dir = System.getenv("CLOUDSTREAM_TEST_HLS_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "index.m3u8").isFile)
        ReferrerFixtureServer("https://ref.example/", dir).use { srv ->
            val p = LibMpvPlayer()
            try {
                p.open(PlaybackSource("${srv.baseUrl}/index.m3u8", emptyMap(), "https://ref.example/", true, false,
                    listOf(SubtitleSource("${srv.baseUrl}/sub.vtt", "en", emptyMap()))))
                val s = runBlocking { withTimeout(20_000) { p.state.first { it.positionMs > 500 } } }
                assertTrue(s.error == null && s.positionMs > 0)
                assertTrue(srv.seenReferers.all { it == "https://ref.example/" })
            } finally { p.release() }
        }
    }
}
