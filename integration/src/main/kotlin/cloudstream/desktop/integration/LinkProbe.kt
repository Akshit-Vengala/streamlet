package cloudstream.desktop.integration

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.PluginConverter
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.storage.StorageManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Network probe of one source family (no playback): runs loadLinks, keeps links whose name contains <filter>, then
 * fetches each playlist -> variant -> first media segment exactly as the player would (same referer / headers / UA)
 * and also without them, printing status, content type, size and a hash of the segment (identical hashes across
 * different titles/servers = a placeholder video, e.g. "disable your VPN").
 *   args: <cs3> <work dir> <query> <filter> [--index N (search hit)]
 */
fun main(args: Array<String>) {
    val cs3 = File(args[0]); val work = File(args[1]).apply { mkdirs() }; val query = args[2]; val filter = args[3]
    val hitIndex = args.indexOf("--index").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 0
    cloudstream.desktop.webview.AndroidWebViewSupport.install()
    com.lagradost.cloudstream3.CommonActivity.activity = androidx.appcompat.app.AppCompatActivity()
    val storage = StorageManager(File(work, "data"))
    ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs")); ShimRuntime.setDataDir(File(work, "files"))
    val jar = PluginConverter(File(work, "jars")).convert(cs3)
    PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() })).load(cs3.nameWithoutExtension, cs3, jar)
    val api = APIHolder.allProviders.first()

    val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
    runBlocking {
        val hit = withTimeout(45_000) { api.search(query, 1) }!!.items[hitIndex]
        println("title: ${hit.name} ${hit.url.take(120)}")
        val loaded = withTimeout(60_000) { api.load(hit.url) }!!
        val data = when (loaded) {
            is AnimeLoadResponse -> loaded.episodes.values.first().first().data
            is TvSeriesLoadResponse -> loaded.episodes.first().data
            is MovieLoadResponse -> loaded.dataUrl
            else -> error("unsupported ${loaded::class}")
        }
        runCatching {
            withTimeout(100_000) { api.loadLinks(data, false, { }, { if (it.name.contains(filter, true) || it.source.contains(filter, true)) links += it }) }
        }.onFailure { println("loadLinks ended: $it") }
    }
    println("matching links: ${links.size}")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    val mpvUa = cloudstream.desktop.player.MpvOptions.DEFAULT_UA
    fun get(url: String, h: Map<String, String>, max: Int = 400_000): Triple<Int, String, ByteArray> = runCatching {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET()
        h.forEach { (k, v) -> runCatching { b.header(k, v) } }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = r.body().use { it.readNBytes(max) }
        Triple(r.statusCode(), r.headers().firstValue("content-type").orElse("?") + " final=" + r.uri().host, bytes)
    }.getOrElse { Triple(-1, "${it::class.simpleName}: ${it.message}", ByteArray(0)) }
    fun resolve(base: String, ref: String) = URI(base).resolve(ref.trim()).toString()
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)

    println("public IP of this machine: " + String(get("https://api.ipify.org", emptyMap()).third))
    for ((n, l) in links.distinctBy { it.url }.withIndex()) {
        println("\n#$n ${l.name} | source=${l.source} q=${l.quality} type=${l.type}")
        println("   url=${l.url}")
        println("   referer=${l.referer}  headers=${l.headers}")
        val asPlayer = LinkedHashMap<String, String>().apply {
            putAll(l.headers.filterKeys { !it.equals("referer", true) && !it.equals("user-agent", true) })
            if (l.referer.isNotBlank()) put("Referer", l.referer)
            put("User-Agent", l.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: mpvUa)
        }
        val csUa = com.lagradost.cloudstream3.USER_AGENT // what CloudStream's ExoPlayer sends when the link has none
        val modes = listOf(
            "as player" to asPlayer,
            "player hdrs + CloudStream UA" to (asPlayer + ("User-Agent" to (l.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: csUa))),
            "no headers, libmpv UA" to mapOf("User-Agent" to mpvUa),
        )
        for ((mode, hdr) in modes) runCatching {
            var url = l.url
            var (code, ct, body) = get(url, hdr)
            var text = String(body)
            print("   [$mode] playlist: $code $ct ${body.size}B")
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (variant != null) { url = resolve(url, variant); val r = get(url, hdr); code = r.first; ct = r.second; body = r.third; text = String(body); print(" -> variant $code ${body.size}B") }
            }
            println()
            val dur = Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDouble() }
            if (mode == "as player") text.lines().filter { it.isNotBlank() }.let { ls -> (if (dur < 300) ls else ls.take(10)).forEach { println("      | ${it.take(200)}") } }
            val segs = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            if (segs.isEmpty() || !text.startsWith("#EXTM3U")) { println("      not a playlist; body starts: ${text.take(160).replace('\n', ' ')}"); return@runCatching }
            val seg = resolve(url, segs.first())
            val (sc, sct, sb) = get(seg, hdr, 300_000)
            println("      ${segs.size} segments, total %.0f s; first segment: $sc $sct ${sb.size}B sha=${sha(sb)} host=${URI(seg).host}".format(dur))
        }.onFailure { println("   [$mode] probe error: $it") }
    }
    storage.close()
    System.out.flush(); Runtime.getRuntime().halt(0)
}
