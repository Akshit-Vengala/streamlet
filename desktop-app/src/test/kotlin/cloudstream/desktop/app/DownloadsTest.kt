package cloudstream.desktop.app

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadsTest {
    @Test fun `helpers`() {
        assertEquals("S1 E2 - What is this", Downloads.safe("S1 E2 - What? is: this"))
        assertEquals("mkv", Downloads.videoExt("https://x/a/b.MKV?token=1"))
        assertEquals("mp4", Downloads.videoExt("https://x/play?id=3"))
        assertEquals("vtt", Downloads.subExt("https://x/sub?id=1", "WEBVTT\n\n".toByteArray()))
        assertEquals("ass", Downloads.subExt("https://x/s.ass", ByteArray(0)))
        assertEquals("srt", Downloads.subExt("https://x/s", "1\n00:00".toByteArray()))
        val ts = ByteArray(188 * 3) { if (it % 188 == 0) 0x47 else 1 }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(60) + ts
        assertContentEquals(ts, Downloads.stripFakeHeader(png))
        assertContentEquals(ts, Downloads.stripFakeHeader(ts))
        // An MP4 fragment whose payload happens to hold a TS sync pattern is left untouched.
        val frag = FakeFmp4.box("moof", ByteArray(800) { b -> if (b % 188 == 10) 0x47 else 3 })
        assertContentEquals(frag, Downloads.stripFakeHeader(frag))
    }

    private fun server(routes: Map<String, (Map<String, String>) -> Pair<Int, ByteArray>>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val h = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                val (code, body) = routes[ex.requestURI.path]?.invoke(h) ?: (404 to "nope".toByteArray())
                if (code == 206) ex.responseHeaders.add("Content-Range", "bytes */*")
                ex.sendResponseHeaders(code, body.size.toLong().takeIf { it > 0 } ?: -1)
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

    private fun waitFor(d: Downloads, id: String, timeoutMs: Long = 20_000, cond: (DownloadItem) -> Boolean): DownloadItem {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { d.byId(id)?.let { if (cond(it)) return it }; Thread.sleep(50) }
        error("timed out: ${d.byId(id)}")
    }

    @Test fun `direct file with subtitle, paused and resumed with a Range request`() {
        val video = ByteArray(3_000_000) { (it % 251).toByte() }
        var ranges = 0
        val srv = server(mapOf(
            "/v.mp4" to { h ->
                val from = h["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
                if (from != null) { ranges++; 206 to video.copyOfRange(from, video.size) }
                else { Thread.sleep(300); 200 to video } // slow enough to pause it
            },
            "/en.vtt" to { _ -> 200 to "WEBVTT\n\n00:00.000 --> 00:01.000\nHi\n".toByteArray() },
        ))
        val tmp = Files.createTempDirectory("dl").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            val d = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }
            val card = MediaCard("Prov", "My Show", "https://show", null)
            val ep = EpisodeItem("ep1", "Pilot", 1, 1, null, null)
            val link = SourceLink("Src", "Src 1080p", "$base/v.mp4", "", 1080, emptyMap(), false, false)
            d.start(card, "My Show", false, ep, link, listOf(SubtitleItem("English", "$base/en.vtt", emptyMap())))
            val id = cloudstream.desktop.app.vm.episodeId("Prov", "ep1")
            d.pause(id)
            // Pretend half was written before the pause: the resume asks only for the rest.
            File(d.byId(id)!!.file).writeBytes(video.copyOfRange(0, 1_000_000))
            d.resume(id)
            val done = waitFor(d, id) { it.status == DownloadItem.Status.Done || it.status == DownloadItem.Status.Failed }
            assertEquals(DownloadItem.Status.Done, done.status, done.error)
            assertContentEquals(video, File(done.file).readBytes())
            assertTrue(ranges >= 1, "resumed with a Range request")
            assertTrue(done.file.endsWith("My Show${File.separator}S1 E1 - Pilot.mp4"), done.file)
            val sub = done.subtitles.orEmpty().single()
            assertTrue(sub.path.endsWith("S1 E1 - Pilot.English.vtt"), sub.path)
            assertTrue(File(sub.path).readText().startsWith("WEBVTT"))
            // Survives a restart (index on disk), and deleting removes the files.
            val again = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }
            assertEquals(done.file, again.finished(id)?.file)
            again.delete(id, files = true)
            assertTrue(!File(done.file).exists() && !File(sub.path).exists())
        } finally { srv.stop(0); scope.cancel(); tmp.deleteRecursively() }
    }

    @Test fun `an adaptive stream is downloaded at the preferred quality or the next one below, never above`() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
        val seg = ByteArray(188 * 10) { if (it % 188 == 0) 0x47 else 7 }
        val master = "#EXTM3U\n" + listOf(360 to 640, 720 to 1280, 960 to 1706, 2160 to 3840).joinToString("") { (h, w) ->
            "#EXT-X-STREAM-INF:BANDWIDTH=${h * 4000},RESOLUTION=${w}x$h\nv$h/index.m3u8\n"
        }
        val hits = java.util.Collections.synchronizedList(ArrayList<String>())
        val routes = HashMap<String, (Map<String, String>) -> Pair<Int, ByteArray>>()
        routes["/master.m3u8"] = { _ -> 200 to master.toByteArray() }
        listOf(360, 720, 960, 2160).forEach { h ->
            routes["/v$h/index.m3u8"] = { _ -> hits += "$h"; 200 to media.toByteArray() }
            routes["/v$h/seg0.ts"] = { _ -> 200 to seg }
        }
        val srv = server(routes)
        val tmp = Files.createTempDirectory("dl").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val d = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }
            val link = SourceLink("Hls", "Hls", "http://127.0.0.1:${srv.address.port}/master.m3u8", "", 0, emptyMap(), true, false)
            fun get(pref: Int, ep: String): DownloadItem {
                d.start(MediaCard("P", "Show", "u", null), "Show", false, EpisodeItem(ep, ep, 1, 1, null, null), link, emptyList(), maxQuality = pref)
                val id = cloudstream.desktop.app.vm.episodeId("P", ep)
                return waitFor(d, id) { it.status == DownloadItem.Status.Done || it.status == DownloadItem.Status.Failed }
            }
            val a = get(1080, "a")
            assertEquals(DownloadItem.Status.Done, a.status, a.error)
            assertEquals(960, a.quality, "1080p preferred, no 1080p: the next one below (960p), not 4K")
            assertEquals(720, get(720, "b").quality)
            assertEquals(360, get(480, "c").quality)
            assertEquals(2160, get(0, "d").quality, "\"Best available\" takes the highest")
            // Each download reads its media playlist twice (segment list, then the #EXT-X-MAP check).
            assertTrue(hits.count { it == "2160" } == 2, "4K fetched only for the Best-available download: $hits")
        } finally { srv.stop(0); scope.cancel(); tmp.deleteRecursively() }
    }

    @Test fun `download best picks the preferred quality among sources`() {
        fun l(q: Int) = SourceLink("S$q", "S$q", "https://x/$q", "", q, emptyMap(), true, false)
        val ordered = listOf(l(2160), l(1080), l(960), l(720))
        assertEquals(1080, pickBest(ordered, 1080) { it.quality }!!.quality)
        assertEquals(960, pickBest(ordered.filter { it.quality != 1080 }, 1080) { it.quality }!!.quality)
        assertEquals(2160, pickBest(listOf(l(2160)), 1080) { it.quality }!!.quality, "only 4K exists: the closest above")
        assertEquals(1080, pickBest(listOf(l(2160), l(0)), 1080) { it.quality }!!.quality.let { if (it == 0) 1080 else it }, "an Auto stream counts as the preferred quality")
    }

    @Test fun `hls playlist is saved segment by segment into one ts file`() {
        val segs = (0 until 5).map { i -> ByteArray(188 * 40) { b -> if (b % 188 == 0) 0x47 else (i + 1).toByte() } }
        val media = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
            segs.indices.forEach { append("#EXTINF:4.0,\nseg$it.ts\n") }
            append("#EXT-X-ENDLIST\n")
        }
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720\nhigh/index.m3u8\n"
        val routes = HashMap<String, (Map<String, String>) -> Pair<Int, ByteArray>>()
        routes["/master.m3u8"] = { _ -> 200 to master.toByteArray() }
        routes["/high/index.m3u8"] = { h -> if (h["referer"] == "https://site/") 200 to media.toByteArray() else 403 to "no referer".toByteArray() }
        segs.forEachIndexed { i, b -> routes["/high/seg$i.ts"] = { _ -> 200 to b } }
        val srv = server(routes)
        val tmp = Files.createTempDirectory("dl").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val d = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }
            val link = SourceLink("Hls", "Hls", "http://127.0.0.1:${srv.address.port}/master.m3u8", "https://site/", 0, emptyMap(), true, false)
            d.start(MediaCard("P", "Film", "u", null), "Film", true, EpisodeItem("m", "Movie", null, null, null, null), link, emptyList())
            val id = cloudstream.desktop.app.vm.episodeId("P", "m")
            val done = waitFor(d, id) { it.status == DownloadItem.Status.Done || it.status == DownloadItem.Status.Failed }
            assertEquals(DownloadItem.Status.Done, done.status, done.error)
            assertContentEquals(segs.reduce { a, b -> a + b }, File(done.file).readBytes())
            assertEquals(5, done.segmentsTotal)
            assertTrue(done.file.endsWith("Film${File.separator}Film.ts"), done.file)
        } finally { srv.stop(0); scope.cancel(); tmp.deleteRecursively() }
    }

    @Test fun `changing the folder mid-download moves the unfinished download, not the finished one`() {
        val video = ByteArray(2_000_000) { (it % 241).toByte() }
        val gate = java.util.concurrent.CountDownLatch(1)
        val srv = server(mapOf(
            "/done.mp4" to { _ -> 200 to video },
            "/v.mp4" to { h ->
                val from = h["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
                if (from != null) 206 to video.copyOfRange(from, video.size)
                else { gate.await(10, java.util.concurrent.TimeUnit.SECONDS); 200 to video } // held until the folder changed
            },
            "/en.vtt" to { _ -> 200 to "WEBVTT\n".toByteArray() },
        ))
        val tmp = Files.createTempDirectory("dl").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            var folder = File(tmp, "old")
            val d = Downloads(File(tmp, "downloads.json"), scope) { folder }
            val card = MediaCard("Prov", "Show", "https://show", null)
            fun link(p: String) = SourceLink("Src", "Src", "$base/$p", "", 1080, emptyMap(), false, false)
            d.start(card, "Show", false, EpisodeItem("e1", "One", 1, 1, null, null), link("done.mp4"), emptyList())
            val finished = waitFor(d, cloudstream.desktop.app.vm.episodeId("Prov", "e1")) { it.status == DownloadItem.Status.Done }
            d.start(card, "Show", false, EpisodeItem("e2", "Two", 1, 2, null, null), link("v.mp4"), listOf(SubtitleItem("English", "$base/en.vtt", emptyMap())))
            val id = cloudstream.desktop.app.vm.episodeId("Prov", "e2")
            waitFor(d, id) { it.status == DownloadItem.Status.Downloading && it.subtitles.orEmpty().isNotEmpty() }

            folder = File(tmp, "new")
            d.relocate()
            waitFor(d, id) { it.file.startsWith(folder.path) }
            gate.countDown()
            val done = waitFor(d, id) { it.status == DownloadItem.Status.Done || it.status == DownloadItem.Status.Failed }
            assertEquals(DownloadItem.Status.Done, done.status, done.error)
            assertEquals(File(folder, "Show${File.separator}S1 E2 - Two.mp4").path, done.file)
            assertContentEquals(video, File(done.file).readBytes())
            val sub = done.subtitles.orEmpty().single()
            assertTrue(sub.path.startsWith(folder.path) && File(sub.path).isFile, sub.path)
            assertEquals(finished.file, d.byId(finished.id)!!.file, "a finished download stays where it is")
            assertTrue(File(finished.file).isFile)
        } finally { gate.countDown(); srv.stop(0); scope.cancel(); tmp.deleteRecursively() }
    }

    @Test fun `fmp4 hls is saved as a plain mp4`() {
        val init = FakeFmp4.init
        val parts = (0 until 3).map { FakeFmp4.segment(it) }
        val segs = parts.map { it.second }
        val media = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"init.mp4\"\n")
            segs.indices.forEach { append("#EXTINF:4.0,\nseg$it.jpg\n") }
            append("#EXT-X-ENDLIST\n")
        }
        val routes = HashMap<String, (Map<String, String>) -> Pair<Int, ByteArray>>()
        routes["/v/index.m3u8"] = { _ -> 200 to media.toByteArray() }
        routes["/v/init.mp4"] = { _ -> 200 to init }
        segs.forEachIndexed { i, b -> routes["/v/seg$i.jpg"] = { _ -> 200 to b } }
        val srv = server(routes)
        val tmp = Files.createTempDirectory("dl").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val d = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }
            val link = SourceLink("Hls", "Hls", "http://127.0.0.1:${srv.address.port}/v/index.m3u8", "", 0, emptyMap(), true, false)
            d.start(MediaCard("P", "Film", "u", null), "Film", true, EpisodeItem("m", "Movie", null, null, null, null), link, emptyList())
            val id = cloudstream.desktop.app.vm.episodeId("P", "m")
            val done = waitFor(d, id) { it.status == DownloadItem.Status.Done || it.status == DownloadItem.Status.Failed }
            assertEquals(DownloadItem.Status.Done, done.status, done.error)
            assertTrue(done.file.endsWith("Film${File.separator}Film.mp4"), done.file)
            val out = File(done.file).readBytes()
            // The init segment was fetched (moov present) and the fragments became one plain MP4 holding every sample.
            assertEquals(listOf("ftyp", "moov", "mdat"), FakeFmp4.topTypes(out))
            val samples = parts.flatMap { it.first }.reduce { a, b -> a + b }
            assertContentEquals(samples, out.copyOfRange(out.size - samples.size, out.size))
            assertTrue(!File(done.file.dropLast(4) + ".ts").exists())
            assertTrue(!File(done.file + ".part").exists())
        } finally { srv.stop(0); scope.cancel(); tmp.deleteRecursively() }
    }
}
