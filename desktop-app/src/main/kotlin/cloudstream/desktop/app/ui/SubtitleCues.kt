package cloudstream.desktop.app.ui

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One subtitle line with its time span (file time, before any sub-delay). */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

private val SRT_TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")

private fun parseTime(s: String): Long? {
    val m = SRT_TIME.matchEntire(s.trim()) ?: return null
    val h = m.groupValues[1].ifEmpty { "0" }.toLong()
    val ms = m.groupValues[4].padEnd(3, '0').take(3).toLong()
    return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + ms
}

/** ASS "h:mm:ss.cc" (centiseconds). */
private fun parseAssTime(s: String): Long? {
    val p = s.trim().split(':', '.')
    if (p.size != 4) return null
    val (h, m, sec, cs) = p.map { it.toLongOrNull() ?: return null }
    return ((h * 60 + m) * 60 + sec) * 1000 + cs * 10
}

private fun cleanTags(s: String) = s
    .replace(Regex("""\{[^}]*\}"""), "")      // ASS override tags
    .replace(Regex("""<[^>]+>"""), "")        // <i>, <font ...>, VTT voice tags
    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/** SRT / WebVTT / ASS-SSA text -> lines sorted by start; unknown formats give an empty list. */
fun parseSubtitleCues(raw: String): List<SubCue> {
    val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
    val out = ArrayList<SubCue>()
    if (text.contains("[Events]", ignoreCase = true) || text.lineSequence().any { it.startsWith("Dialogue:") }) {
        // ASS: the Format line says where Start / End / Text are.
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("Format:", true)) fields = l.substringAfter(':').split(',').map { it.trim().lowercase() }
            if (!l.startsWith("Dialogue:", true)) continue
            val parts = l.substringAfter(':').split(',', limit = fields.size).map { it.trim() }
            val st = parseAssTime(parts.getOrNull(fields.indexOf("start")) ?: continue) ?: continue
            val en = parseAssTime(parts.getOrNull(fields.indexOf("end")) ?: continue) ?: continue
            val t = cleanTags(parts.getOrNull(fields.indexOf("text").takeIf { it >= 0 } ?: parts.lastIndex).orEmpty())
            if (t.isNotEmpty()) out += SubCue(st, en, t)
        }
    } else {
        // SRT and WebVTT: blocks separated by blank lines, each with a "start --> end" line.
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val i = lines.indexOfFirst { ARROW.containsMatchIn(it) }
            if (i < 0) continue
            val m = ARROW.find(lines[i])!!
            val st = parseTime(m.groupValues[1]) ?: continue
            val en = parseTime(m.groupValues[2]) ?: continue
            val t = cleanTags(lines.drop(i + 1).joinToString("\n"))
            if (t.isNotEmpty()) out += SubCue(st, en, t)
        }
    }
    return out.sortedBy { it.startMs }
}

/** The line showing at file time [t] (the last one started, if still on), else null. */
fun activeCueIndex(cues: List<SubCue>, t: Long): Int? {
    var lo = 0; var hi = cues.size - 1; var best = -1
    while (lo <= hi) { val mid = (lo + hi) / 2; if (cues[mid].startMs <= t) { best = mid; lo = mid + 1 } else hi = mid - 1 }
    if (best < 0) return null
    // Overlapping lines: the latest one still on wins.
    for (k in best downTo maxOf(0, best - 5)) if (t < cues[k].endMs) return k
    return null
}

/** The line to scroll to at [t]: the active one, else the next to come. */
fun nearestCueIndex(cues: List<SubCue>, t: Long): Int =
    activeCueIndex(cues, t) ?: cues.indexOfFirst { it.startMs > t }.let { if (it < 0) cues.lastIndex else it }.coerceAtLeast(0)

/** Downloads (or reads) a subtitle file and parses it; null when it can't be read. */
object SubtitleCueLoader {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<SubCue>>()
    private val client by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    }

    fun load(location: String, headers: Map<String, String> = emptyMap()): List<SubCue>? {
        cache[location]?.let { return it }
        val bytes = runCatching {
            if (location.startsWith("http://", true) || location.startsWith("https://", true)) {
                val b = HttpRequest.newBuilder(URI(location)).timeout(Duration.ofSeconds(30))
                if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", cloudstream.desktop.app.sync.Http.UA)
                headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
                val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
                if (r.statusCode() !in 200..299) return null
                val enc = r.headers().firstValue("content-encoding").orElse("")
                if (enc.equals("gzip", true)) java.util.zip.GZIPInputStream(r.body().inputStream()).readBytes() else r.body()
            } else File(location.removePrefix("file://")).takeIf { it.isFile }?.readBytes()
        }.getOrNull() ?: return null
        val text = decode(bytes)
        return parseSubtitleCues(text).takeIf { it.isNotEmpty() }?.also { cache[location] = it }
    }

    /** UTF-8 (BOM or valid), UTF-16 by BOM, else Windows-1252. */
    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE)
        val dec = Charsets.UTF_8.newDecoder()
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrElse { String(b, charset("windows-1252")) }
    }
}
