package cloudstream.desktop.app

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A subtitle saved next to a downloaded video. */
data class DownloadedSub(val lang: String, val path: String)

/** One episode (or film) download. Saved in `downloads.json`; [id] is the episode id used for watch progress. */
data class DownloadItem(
    val id: String,
    val provider: String, val cardUrl: String, val titleName: String, val posterUrl: String?,
    val episodeData: String, val episodeName: String?, val season: Int?, val episode: Int?, val episodeLabel: String,
    val episodePoster: String?, val isMovie: Boolean,
    val sourceName: String, val quality: Int,
    val url: String, val referer: String, val headers: Map<String, String>, val isM3u8: Boolean,
    /** Where the video is written. */
    val file: String,
    /** Subtitles still to fetch (they are fetched first) and the ones saved. */
    val pendingSubs: List<SubtitleItem>?, val subtitles: List<DownloadedSub>?,
    val status: Status,
    val bytes: Long = 0, val total: Long = 0,
    /** HLS: segments written so far (resume point) of [segmentsTotal]. */
    val segmentsDone: Int = 0, val segmentsTotal: Int = 0,
    val error: String? = null,
    val addedAt: Long,
    /** Highest resolution to take from an adaptive (HLS) stream: the preferred quality when it started; 0 = best. */
    val maxQuality: Int = 0,
) {
    enum class Status { Queued, Downloading, Paused, Done, Failed }

    /** 0..1, or null while unknown. */
    val progress: Float?
        get() = when {
            status == Status.Done -> 1f
            segmentsTotal > 0 -> segmentsDone.toFloat() / segmentsTotal
            total > 0 -> (bytes.toFloat() / total).coerceIn(0f, 1f)
            else -> null
        }
    val active: Boolean get() = status == Status.Queued || status == Status.Downloading

    fun card() = MediaCard(provider, titleName, cardUrl, posterUrl)
    fun episodeItem() = EpisodeItem(episodeData, episodeName, season, episode, episodePoster, null)
    /** The saved file as a source the player opens directly. */
    fun localLink() = SourceLink("Downloaded", "$sourceName (downloaded)", File(file).absolutePath, "", quality, emptyMap(), false, false)
    fun localSubs() = subtitles.orEmpty().filter { File(it.path).isFile }.map { SubtitleItem(it.lang, File(it.path).absolutePath, emptyMap()) }
}

/**
 * The downloader (CloudStream's VideoDownloadManager, desktop flavour): HLS through the library's
 * [com.lagradost.cloudstream3.utils.M3u8Helper2.hslLazy] (best variant with audio, AES-128 decrypted) written segment
 * by segment to a .ts file, direct files over HTTP with Range resume. Several downloads run at once ([maxParallel]),
 * each one resumable after a pause, a failure or a restart of the app.
 */
class Downloads(private val indexFile: File, private val scope: CoroutineScope, private val folder: () -> File) {
    private val log = AppLog.get("downloads")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile var maxParallel = 3
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()

    init {
        // Interrupted by a restart: queue them again.
        _items.update { l -> l.map { if (it.status == DownloadItem.Status.Downloading) it.copy(status = DownloadItem.Status.Queued) else it } }
        pump()
    }

    fun byId(id: String) = _items.value.firstOrNull { it.id == id }

    /** Screenshots / demos: show these entries without starting anything. */
    internal fun showOnly(list: List<DownloadItem>) { jobs.values.forEach { it.cancel() }; jobs.clear(); _items.value = list }

    /** Downloaded and still on disk. */
    fun finished(id: String) = byId(id)?.takeIf { it.status == DownloadItem.Status.Done && File(it.file).isFile }

    /** Starts downloading [episode] of [card] from [link] (replacing an earlier download of it), with [subs]. */
    fun start(card: MediaCard, titleName: String, isMovie: Boolean, episode: EpisodeItem, link: SourceLink, subs: List<SubtitleItem>, maxQuality: Int = 0) {
        val id = cloudstream.desktop.app.vm.episodeId(card.provider, episode.data)
        delete(id, files = true)
        val dir = File(folder(), safe(titleName)).apply { mkdirs() }
        val base = if (isMovie) safe(titleName) else safe(episode.label)
        val ext = if (link.isM3u8) "ts" else videoExt(link.url)
        val item = DownloadItem(
            id, card.provider, card.url, titleName, card.posterUrl,
            episode.data, episode.name, episode.season, episode.episode, episode.label, episode.posterUrl, isMovie,
            link.source.ifBlank { link.name }, link.quality, link.url, link.referer, link.headers, link.isM3u8,
            File(dir, "$base.$ext").absolutePath, subs, emptyList(), DownloadItem.Status.Queued, addedAt = System.currentTimeMillis(),
            maxQuality = maxQuality,
        )
        _items.update { it + item }
        save(); pump()
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        set(id) { if (it.active) it.copy(status = DownloadItem.Status.Paused) else it }
        pump()
    }

    fun resume(id: String) { set(id) { if (it.status == DownloadItem.Status.Paused || it.status == DownloadItem.Status.Failed) it.copy(status = DownloadItem.Status.Queued, error = null) else it }; pump() }

    /** Removes the entry; [files]: also the video and its subtitles. */
    fun delete(id: String, files: Boolean) {
        jobs.remove(id)?.cancel()
        val it = byId(id) ?: return
        _items.update { l -> l.filterNot { x -> x.id == id } }
        save()
        if (files) {
            (listOf(it.file) + it.subtitles.orEmpty().map { s -> s.path }).forEach { p -> runCatching { File(p).delete() } }
            File(it.file).parentFile?.takeIf { d -> d.isDirectory && d.list().isNullOrEmpty() }?.delete()
        }
        pump()
    }

    private fun set(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    @Synchronized private fun pump() {
        val running = _items.value.count { it.status == DownloadItem.Status.Downloading }
        _items.value.filter { it.status == DownloadItem.Status.Queued }.take((maxParallel - running).coerceAtLeast(0)).forEach { item ->
            set(item.id) { it.copy(status = DownloadItem.Status.Downloading, error = null) }
            jobs[item.id] = scope.launch {
                val r = runCatching { run(item.id) }
                jobs.remove(item.id)
                r.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) return@launch
                    log.warning("Download of ${item.titleName} ${item.episodeLabel} failed: $e")
                    set(item.id) { it.copy(status = DownloadItem.Status.Failed, error = e.message ?: e.javaClass.simpleName) }
                }
                pump()
            }
        }
    }

    private suspend fun run(id: String) {
        val item = byId(id) ?: return
        log.info("Downloading ${item.titleName} ${item.episodeLabel} from ${item.sourceName} (${item.quality}p) to ${item.file}")
        fetchSubtitles(id)
        if (item.isM3u8) hls(id) else direct(id)
        set(id) { it.copy(status = DownloadItem.Status.Done, bytes = File(it.file).length(), total = File(it.file).length()) }
        log.info("Downloaded ${item.titleName} ${item.episodeLabel}: ${File(item.file).length() / 1_048_576} MB")
    }

    private fun headersOf(item: DownloadItem): Map<String, String> {
        val h = LinkedHashMap(item.headers)
        if (item.referer.isNotBlank() && h.keys.none { it.equals("referer", true) }) h["Referer"] = item.referer
        if (h.keys.none { it.equals("user-agent", true) }) h["User-Agent"] = UA
        return h
    }

    private fun fetchSubtitles(id: String) {
        val item = byId(id) ?: return
        val pending = item.pendingSubs.orEmpty()
        if (pending.isEmpty()) return
        val base = File(item.file).let { File(it.parentFile, it.nameWithoutExtension) }
        val saved = item.subtitles.orEmpty().toMutableList()
        val used = HashSet<String>()
        pending.forEach { s ->
            runCatching {
                val res = http.send(request(s.url, s.headers + mapOf("User-Agent" to UA)), HttpResponse.BodyHandlers.ofByteArray())
                check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
                val body = res.body()
                val ext = subExt(s.url, body)
                var name = "${base.path}.${safe(s.lang).ifBlank { "sub" }}"
                var n = 2
                while (!used.add(name)) name = "${base.path}.${safe(s.lang)}.${n++}"
                val f = File("$name.$ext")
                f.writeBytes(body)
                saved += DownloadedSub(s.lang, f.absolutePath)
            }.onFailure { log.info("Subtitle ${s.lang} not saved: $it") }
        }
        set(id) { it.copy(pendingSubs = emptyList(), subtitles = saved) }
    }

    /** HLS: segments in order (a few fetched ahead), appended to the .ts file; resumes after the last whole segment. */
    private suspend fun hls(id: String) = kotlinx.coroutines.coroutineScope {
        val item = byId(id) ?: return@coroutineScope
        val (playlist, height) = pickVariant(item)
        if (height > 0 && height != item.quality) set(id) { it.copy(quality = height) }
        val data = com.lagradost.cloudstream3.utils.M3u8Helper2.hslLazy(
            com.lagradost.cloudstream3.utils.M3u8Helper.M3u8Stream(playlist, height.takeIf { it > 0 }, headersOf(item)),
            selectBest = true, requireAudio = true,
        )
        val total = data.size
        check(total > 0) { "The playlist has no segments" }
        val start = byId(id)!!.segmentsDone.coerceIn(0, total)
        RandomAccessFile(item.file, "rw").use { out ->
            out.setLength(if (start == 0) 0 else byId(id)!!.bytes.coerceAtMost(out.length()))
            out.seek(out.length())
            set(id) { it.copy(segmentsTotal = total) }
            val ahead = ArrayDeque<Deferred<ByteArray?>>()
            var next = start
            fun fill() { while (ahead.size < PARALLEL_SEGMENTS && next < total) { val i = next++; ahead.addLast(async { data.resolveLinkSafe(i, tries = 4) }) } }
            fill()
            var done = start
            var lastSave = 0L
            while (ahead.isNotEmpty() && isActive) {
                val bytes = ahead.removeFirst().await() ?: error("Segment ${done + 1} of $total could not be downloaded")
                out.write(stripFakeHeader(bytes))
                done++
                fill()
                val now = System.currentTimeMillis()
                val len = out.length()
                _items.update { l -> l.map { if (it.id == id) it.copy(segmentsDone = done, bytes = len) else it } }
                if (now - lastSave > 2000) { save(); lastSave = now }
            }
        }
    }

    /**
     * A master playlist lists several resolutions; hslLazy always takes the highest. Pick the one that fits
     * [DownloadItem.maxQuality] instead (highest at or below it, else the lowest above it; see [pickBest]), among
     * variants that carry their own audio. Returns the playlist to download and its height (0 = unknown).
     */
    private fun pickVariant(item: DownloadItem): Pair<String, Int> {
        val res = http.send(request(item.url, headersOf(item)), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() in 200..299) { "Playlist: HTTP ${res.statusCode()}" }
        val variants = masterVariants(res.uri().toString(), res.body())
        if (variants.isEmpty()) return item.url to item.quality // a media playlist already, or nothing usable: hslLazy decides
        val best = pickBest(variants.sortedByDescending { it.bandwidth }, item.maxQuality) { v -> v.height.takeIf { it > 0 } ?: 400 }!!
        log.info("${item.titleName} ${item.episodeLabel}: ${best.height}p of ${variants.map { it.height }.distinct()} (preferred ${item.maxQuality.takeIf { it > 0 }?.let { "${it}p" } ?: "best"})")
        return best.url to best.height
    }

    /** A direct file: Range request from what is already on disk. */
    private suspend fun direct(id: String) {
        val item = byId(id) ?: return
        val f = File(item.file)
        val have = if (f.isFile) f.length() else 0L
        val h = headersOf(item) + (if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap())
        val res = http.send(request(item.url, h), HttpResponse.BodyHandlers.ofInputStream())
        val code = res.statusCode()
        check(code in 200..299) { "HTTP $code" }
        val append = code == 206 && have > 0
        val len = res.headers().firstValueAsLong("content-length").orElse(-1)
        val total = if (len > 0) (if (append) have + len else len) else 0
        val type = res.headers().firstValue("content-type").orElse("")
        check(!type.startsWith("text/html")) { "The server sent a web page, not a video" }
        set(id) { it.copy(total = total, bytes = if (append) have else 0) }
        res.body().use { input ->
            java.io.FileOutputStream(f, append).use { out ->
                val buf = ByteArray(256 * 1024)
                var written = if (append) have else 0L
                var lastSave = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().let { ctx -> if (!ctx.isActive) throw CancellationException() }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 500) {
                        _items.update { l -> l.map { if (it.id == id) it.copy(bytes = written) else it } }
                        if (now - lastSave > 2000) save()
                        lastSave = now
                    }
                }
                check(total <= 0 || written >= total) { "Connection closed early (${written / 1_048_576} of ${total / 1_048_576} MB)" }
            }
        }
    }

    private fun request(url: String, headers: Map<String, String>): HttpRequest {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET()
        headers.forEach { (k, v) -> runCatching { b.setHeader(k, v) } } // restricted names (Host, Connection) are skipped
        return b.build()
    }

    private fun read(): List<DownloadItem> = runCatching {
        if (!indexFile.isFile) return emptyList()
        gson.fromJson<List<DownloadItem>>(indexFile.readText(), object : TypeToken<List<DownloadItem>>() {}.type).orEmpty()
    }.onFailure { log.warning("downloads.json unreadable: $it") }.getOrDefault(emptyList())

    @Synchronized private fun save() {
        runCatching {
            indexFile.parentFile?.mkdirs()
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(gson.toJson(_items.value))
            java.nio.file.Files.move(tmp.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { log.warning("downloads.json not saved: $it") }
    }

    companion object {
        const val PARALLEL_SEGMENTS = 4
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

        data class Variant(val url: String, val height: Int, val bandwidth: Long)

        /**
         * The variants of a master playlist that carry their own audio (one whose AUDIO group points at separate
         * tracks would need muxing, like CloudStream's requireAudio). Empty for a media playlist.
         */
        fun masterVariants(baseUrl: String, text: String): List<Variant> {
            fun attr(line: String, name: String) =
                Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }
            val lines = text.lines().map { it.trim() }
            val separateAudio = lines.filter { it.startsWith("#EXT-X-MEDIA:") && attr(it, "TYPE") == "AUDIO" && attr(it, "URI") != null }
                .mapNotNull { attr(it, "GROUP-ID") }.toSet()
            val out = ArrayList<Variant>()
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                if (attr(line, "AUDIO")?.let { it in separateAudio } == true) return@forEachIndexed
                val height = attr(line, "RESOLUTION")?.substringAfter('x')?.toIntOrNull() ?: 0
                val bw = (attr(line, "AVERAGE-BANDWIDTH") ?: attr(line, "BANDWIDTH"))?.toLongOrNull() ?: 0
                out += Variant(runCatching { URI(baseUrl).resolve(uri).toString() }.getOrDefault(uri), height, bw)
            }
            return out
        }

        /** A file name safe on Windows and Linux. */
        fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120)

        fun videoExt(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
            return listOf("mp4", "mkv", "webm", "avi", "mov", "m4v").firstOrNull { path.endsWith(".$it") } ?: "mp4"
        }

        fun subExt(url: String, body: ByteArray): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
            listOf("vtt", "srt", "ass", "ssa").firstOrNull { path.endsWith(".$it") }?.let { return it }
            val head = String(body, 0, minOf(body.size, 64), Charsets.UTF_8).trimStart('﻿').trimStart()
            return when {
                head.startsWith("WEBVTT") -> "vtt"
                head.startsWith("[Script Info]") -> "ass"
                else -> "srt"
            }
        }

        /**
         * Some hosts disguise MPEG-TS segments as images (a PNG/JPEG header in front). Players skip it while
         * streaming; a saved file must not contain it, so cut everything before the first TS sync pattern.
         */
        fun stripFakeHeader(b: ByteArray): ByteArray {
            if (b.isEmpty() || b[0] == 0x47.toByte()) return b
            val limit = minOf(b.size - 377, 4096)
            for (i in 0 until limit.coerceAtLeast(0)) {
                if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return b.copyOfRange(i, b.size)
            }
            return b
        }
    }
}
