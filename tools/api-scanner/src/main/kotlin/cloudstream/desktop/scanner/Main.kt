package cloudstream.desktop.scanner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Track D CLI. Downloads every .cs3 of the given plugin lists (SHA-256 verified), converts it with dex2jar,
 * then statically analyzes it with ASM. Plugins are NEVER loaded or executed.
 */
class Options(args: Array<String>) {
    val repos = mutableListOf<String>()           // plugins.json URL or local path
    var libraryJar = File("spike/libs/library-jvm-1.0.1.jar")
    var libDir = File("spike/libs")
    var dex2jarDir = File("tools/bin/dex-tools-2.4.38")
    var work = File("tools/api-scanner/build/work")
    var outMd = File("reports/api-usage.md")
    var outJson = File("reports/api-usage.json")
    var threads = 4
    var only: Set<String>? = null
    var priority = emptySet<String>() // --priority A,B: extensions listed in full
    var topMembers = 150

    init {
        var i = 0
        fun next() = args[++i]
        while (i < args.size) {
            when (val a = args[i]) {
                "--repo" -> repos += next()
                "--library-jar" -> libraryJar = File(next())
                "--lib-dir" -> libDir = File(next())
                "--dex2jar-dir" -> dex2jarDir = File(next())
                "--work" -> work = File(next())
                "--out-md" -> outMd = File(next())
                "--out-json" -> outJson = File(next())
                "--threads" -> threads = next().toInt()
                "--only" -> only = next().split(',').map { it.trim() }.toSet()
                "--priority" -> priority = next().split(',').map { it.trim() }.toSet()
                "--top-members" -> topMembers = next().toInt()
                else -> { System.err.println("unknown arg $a"); exitProcess(2) }
            }
            i++
        }
        if (repos.isEmpty()) {
            System.err.println(
                "usage: --repo <plugins.json url|path> [--repo ...] [--library-jar f] [--lib-dir d] [--dex2jar-dir d]\n" +
                    "       [--work d] [--out-md f] [--out-json f] [--threads n] [--only A,B] [--priority A,B] [--top-members n]"
            )
            exitProcess(2)
        }
    }
}

class PluginResult(
    val repo: String,
    val internalName: String,
    val name: String,
    val version: Int?,
    val status: Int?,
    val sha256Ok: Boolean?,
    var error: String? = null,
    var pluginClassName: String? = null,
    var requiresResources: Boolean = false,
    var hasNativeSo: Boolean = false,
    var nativeSoFiles: List<String> = emptyList(),
    var usesWebView: Boolean = false,
    var usesCloudflareKiller: Boolean = false,
    var usesAndroidUi: Boolean = false,
    var extendsAppOnlyPlugin: Boolean = false,
    var loadsNativeLibrary: Boolean = false,
    var internalClasses: Int = 0,
    val categoryCounts: MutableMap<String, Int> = sortedMapOf(),
    /** internal class name -> category, for every non-resolvable reference (android/androidx/app-only/other). */
    val missingClasses: MutableMap<String, String> = sortedMapOf(),
    /** "owner.name:desc" of members on missing classes (what the shim must provide). */
    val missingClassMembers: MutableSet<String> = sortedSetOf(),
    /** "owner.name:desc" of members missing on classes that DO exist (library gaps / version skew). */
    val missingMembersOnKnownClasses: MutableSet<String> = sortedSetOf(),
)

private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
private val mapper = ObjectMapper()

private fun fetchBytes(url: String): ByteArray {
    val res = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "cloudstream-desktop-scanner").build(),
        HttpResponse.BodyHandlers.ofByteArray())
    require(res.statusCode() == 200) { "HTTP ${res.statusCode()} for $url" }
    return res.body()
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val o = Options(args)
    o.work.mkdirs()
    val depJars = o.libDir.listFiles { f -> f.name.endsWith(".jar") }!!.toList()
    val index = ClassIndex(o.libraryJar, depJars)
    println("Indexed ${index.coreSize()} library classes (+${depJars.size} dependency jars)")

    // Collect plugin entries from every repo list.
    data class Entry(val repo: String, val node: JsonNode)
    val entries = mutableListOf<Entry>()
    for (r in o.repos) {
        val text = if (r.startsWith("http")) String(fetchBytes(r)) else File(r).readText()
        mapper.readTree(text).forEach { entries += Entry(r.substringBefore("/builds/").substringAfterLast('/').ifBlank { r }, it) }
    }
    val selected = entries.filter { o.only == null || it.node["internalName"].asText() in o.only!! }
    println("${selected.size} plugin entries")

    val pool = Executors.newFixedThreadPool(o.threads)
    val futures = selected.map { e ->
        pool.submit<PluginResult> { processPlugin(o, index, e.repo, e.node) }
    }
    val results = futures.map { it.get() }
    pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)

    mapper.enable(SerializationFeature.INDENT_OUTPUT)
    o.outJson.absoluteFile.parentFile.mkdirs()
    writeJson(o, results)
    writeMarkdown(o, results)
    val ok = results.count { it.error == null }
    println("Done: $ok scanned, ${results.size - ok} failed. Wrote ${o.outMd} and ${o.outJson}")
}

private fun processPlugin(o: Options, index: ClassIndex, repo: String, n: JsonNode): PluginResult {
    val internal = n["internalName"].asText()
    val repoTag = repo.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(40)
    val r = PluginResult(repo, internal, n["name"]?.asText() ?: internal, n["version"]?.asInt(), n["status"]?.asInt(), null)
    try {
        val dir = File(o.work, "cs3").apply { mkdirs() }
        val cs3 = File(dir, "${repo.hashCode().toUInt()}_$internal.cs3")
        val want = n["fileHash"]?.asText()?.removePrefix("sha256-")
        val bytes = if (cs3.exists() && (want == null || sha256(cs3.readBytes()) == want)) cs3.readBytes()
        else fetchBytes(n["url"].asText()).also { cs3.writeBytes(it) }
        val got = sha256(bytes)
        val shaOk = want?.let { it.equals(got, true) }
        val res = PluginResult(repo, internal, r.name, r.version, r.status, shaOk)
        if (shaOk == false) { res.error = "sha256 mismatch (expected $want, got $got); not analyzed"; cs3.delete(); return res }

        ZipFile(cs3).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            res.nativeSoFiles = names.filter { it.endsWith(".so") }
            res.hasNativeSo = res.nativeSoFiles.isNotEmpty()
            z.getEntry("manifest.json")?.let {
                val m = mapper.readTree(z.getInputStream(it).readBytes())
                res.pluginClassName = m["pluginClassName"]?.asText()
                res.requiresResources = m["requiresResources"]?.asBoolean() == true
            }
        }

        val jarDir = File(o.work, "jar").apply { mkdirs() }
        val jar = File(jarDir, "${repo.hashCode().toUInt()}_$internal.jar")
        if (!jar.exists() || jar.lastModified() < cs3.lastModified()) {
            val pb = ProcessBuilder(
                "java", "-cp", File(o.dex2jarDir, "lib").absolutePath + File.separator + "*",
                "com.googlecode.dex2jar.tools.Dex2jarCmd", "-f", "-o", jar.absolutePath, cs3.absolutePath,
            ).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.readBytes().decodeToString()
            if (p.waitFor() != 0 || !jar.exists()) { res.error = "dex2jar failed: ${out.takeLast(300)}"; return res }
        }
        analyze(index, jar, res)
        return res
    } catch (t: Throwable) {
        r.error = "${t.javaClass.simpleName}: ${t.message}"
        return r
    }
}

private fun analyze(index: ClassIndex, jar: File, r: PluginResult) {
    val refs = scanJar(jar)
    r.internalClasses = refs.internalClasses
    val cats = HashMap<String, Cat>()
    for (c in refs.classes) {
        val cat = categorize(c, index)
        cats[c] = cat
        r.categoryCounts.merge(cat.name, 1, Int::plus)
        if (cat == Cat.ANDROID || cat == Cat.ANDROIDX || cat == Cat.APP_ONLY || cat == Cat.MISSING_OTHER) r.missingClasses[c] = cat.name
        if (WEBVIEW_MARKERS.any { c.startsWith(it) }) r.usesWebView = true
        if (CLOUDFLARE_MARKERS.any { c.startsWith(it) }) r.usesCloudflareKiller = true
        if (UI_PREFIXES.any { c.startsWith(it) }) r.usesAndroidUi = true
    }
    r.extendsAppOnlyPlugin = "com/lagradost/cloudstream3/plugins/Plugin" in refs.superclasses
    for (m in refs.members) {
        val owner = m.substringBefore('.')
        val rest = m.substringAfter('.')
        val name = rest.substringBefore(':'); val desc = rest.substringAfter(':')
        if (owner == "java/lang/System" && name == "loadLibrary" || owner == "java/lang/Runtime" && (name == "loadLibrary" || name == "load")) r.loadsNativeLibrary = true
        when (cats[owner]) {
            Cat.ANDROID, Cat.ANDROIDX, Cat.APP_ONLY, Cat.MISSING_OTHER -> r.missingClassMembers += m
            Cat.CORE, Cat.OTHER_LIB, Cat.KOTLIN, Cat.JDK ->
                // D8/dex2jar turns the '-' of Kotlin inline-class mangled names into '_'; retry with it restored.
                if (!index.hasMember(owner, name, desc) &&
                    !(name.contains('_') && index.hasMember(owner, name.substring(0, name.lastIndexOf('_')) + "-" + name.substringAfterLast('_'), desc))
                ) r.missingMembersOnKnownClasses += m
            null -> {}
        }
    }
}

// ------------------------------------------------------------------ output

private fun dotted(s: String) = s.replace('/', '.')
private fun prettyMember(m: String): String {
    val owner = m.substringBefore('.'); val rest = m.substringAfter('.')
    val desc = rest.substringAfter(':')
    return "${dotted(owner)}.${rest.substringBefore(':')}${if (desc.startsWith("(")) "" else " : "}$desc"
}

private fun writeJson(o: Options, results: List<PluginResult>) {
    val ok = results.filter { it.error == null }
    fun rank(sel: (PluginResult) -> Collection<String>) =
        ok.flatMap { p -> sel(p).map { it to p.internalName } }.groupBy({ it.first }, { it.second })
            .map { (k, v) -> mapOf("symbol" to k, "pluginCount" to v.toSet().size, "plugins" to v.toSet().sorted()) }
            .sortedWith(compareByDescending<Map<String, Any>> { it["pluginCount"] as Int }.thenBy { it["symbol"] as String })
    val root = mapOf(
        "generated" to java.time.Instant.now().toString(),
        "pluginsTotal" to results.size, "pluginsScanned" to ok.size,
        "failed" to results.filter { it.error != null }.map { mapOf("plugin" to it.internalName, "error" to it.error) },
        "missingClassesRanked" to rank { it.missingClasses.keys }.map { s ->
            s + ("category" to ok.first { p -> p.missingClasses.containsKey(s["symbol"]) }.missingClasses[s["symbol"]])
        },
        "missingMembersRanked" to rank { it.missingClassMembers },
        "missingMembersOnKnownClassesRanked" to rank { it.missingMembersOnKnownClasses },
        "plugins" to results.map { it },
    )
    mapper.writerWithDefaultPrettyPrinter().writeValue(o.outJson, root)
}

private fun writeMarkdown(o: Options, results: List<PluginResult>) {
    val ok = results.filter { it.error == null }
    val sb = StringBuilder()
    fun pct(n: Int) = "${n}/${ok.size} (${if (ok.isEmpty()) 0 else n * 100 / ok.size}%)"
    sb.appendLine("# API usage report (Track D)\n")
    sb.appendLine("Generated by `tools/api-scanner` (ASM static analysis of dex2jar output; plugins are never executed).")
    sb.appendLine("Classified against upstream `library-jvm` + its runtime deps + the JDK. JSON with all data: `reports/api-usage.json`.\n")
    sb.appendLine("- Plugins listed: ${results.size}; analyzed: ${ok.size}; failed: ${results.size - ok.size}")
    sb.appendLine("- Repos: " + results.map { it.repo }.distinct().joinToString(", "))
    results.filter { it.error != null }.forEach { sb.appendLine("  - FAILED `${it.internalName}`: ${it.error}") }
    sb.appendLine()

    sb.appendLine("## Summary flags\n")
    sb.appendLine("| Flag | Plugins |\n|---|---|")
    sb.appendLine("| extends app-only `Plugin` | ${pct(ok.count { it.extendsAppOnlyPlugin })} |")
    sb.appendLine("| requiresResources (res/ + resources.arsc) | ${pct(ok.count { it.requiresResources })} |")
    sb.appendLine("| uses WebView (`android.webkit.*` or `WebViewResolver`) | ${pct(ok.count { it.usesWebView })} |")
    sb.appendLine("| uses `CloudflareKiller` | ${pct(ok.count { it.usesCloudflareKiller })} |")
    sb.appendLine("| uses Android UI classes (view/widget/app/fragment/...) | ${pct(ok.count { it.usesAndroidUi })} |")
    sb.appendLine("| ships native `.so` | ${pct(ok.count { it.hasNativeSo })} |")
    sb.appendLine("| calls `System.loadLibrary`/`Runtime.load` | ${pct(ok.count { it.loadsNativeLibrary })} |")
    sb.appendLine("| has members missing on classes that exist in core/JDK | ${pct(ok.count { it.missingMembersOnKnownClasses.isNotEmpty() })} |")
    sb.appendLine()

    val byClass = ok.flatMap { p -> p.missingClasses.map { (c, cat) -> Triple(c, cat, p.internalName) } }
        .groupBy { it.first }
    val catOrder = listOf("APP_ONLY", "ANDROID", "ANDROIDX", "MISSING_OTHER")
    val titles = mapOf(
        "APP_ONLY" to "App-only CloudStream classes (`com.lagradost.*` not in `library`)",
        "ANDROID" to "`android.*` classes", "ANDROIDX" to "`androidx.*` classes",
        "MISSING_OTHER" to "Other unresolved classes (not bundled in the plugin, not in library/JDK)",
    )
    sb.appendLine("## Missing classes, ranked by number of plugins\n")
    for (cat in catOrder) {
        val rows = byClass.filter { (_, v) -> v.first().second == cat }
            .map { (c, v) -> c to v.map { it.third }.toSet() }.sortedWith(compareByDescending<Pair<String, Set<String>>> { it.second.size }.thenBy { it.first })
        sb.appendLine("### ${titles[cat]} (${rows.size} classes)\n")
        if (rows.isEmpty()) { sb.appendLine("None.\n"); continue }
        sb.appendLine("| # | Class | Plugins |\n|---|---|---|")
        rows.forEachIndexed { i, (c, ps) -> sb.appendLine("| ${i + 1} | `${dotted(c)}` | ${ps.size} |") }
        sb.appendLine()
    }

    sb.appendLine("## Missing members/signatures on missing classes, ranked (top ${o.topMembers})\n")
    sb.appendLine("These are what the shim must implement. Format: `owner.name(desc)` using JVM descriptors.\n")
    val byMember = ok.flatMap { p -> p.missingClassMembers.map { it to p.internalName } }.groupBy({ it.first }, { it.second })
        .map { (m, ps) -> m to ps.toSet().size }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    sb.appendLine("Total distinct signatures: ${byMember.size}\n")
    sb.appendLine("| # | Signature | Plugins |\n|---|---|---|")
    byMember.take(o.topMembers).forEachIndexed { i, (m, n) -> sb.appendLine("| ${i + 1} | `${prettyMember(m)}` | $n |") }
    sb.appendLine()

    sb.appendLine("## Members missing on classes that exist in core/JDK/libs\n")
    val gaps = ok.flatMap { p -> p.missingMembersOnKnownClasses.map { it to p.internalName } }.groupBy({ it.first }, { it.second })
        .map { (m, ps) -> m to ps.toSet().size }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    if (gaps.isEmpty()) sb.appendLine("None.\n") else {
        sb.appendLine("Library version skew or Android-only JDK additions; Track A/C should consider these.\n")
        sb.appendLine("| # | Signature | Plugins |\n|---|---|---|")
        gaps.take(100).forEachIndexed { i, (m, n) -> sb.appendLine("| ${i + 1} | `${prettyMember(m)}` | $n |") }
        if (gaps.size > 100) sb.appendLine("\n(${gaps.size - 100} more in JSON)")
        sb.appendLine()
    }

    sb.appendLine("## Per-plugin overview\n")
    sb.appendLine("Flags: W=WebView, C=CloudflareKiller, U=Android UI, R=requiresResources, N=native .so/loadLibrary, P=extends app-only Plugin.\n")
    sb.appendLine("| Plugin | Repo | Flags | Missing classes | Missing signatures | Known-class gaps |\n|---|---|---|---|---|---|")
    for (p in ok.sortedWith(compareByDescending<PluginResult> { it.missingClasses.size }.thenBy { it.internalName })) {
        val f = buildString {
            if (p.usesWebView) append('W'); if (p.usesCloudflareKiller) append('C'); if (p.usesAndroidUi) append('U')
            if (p.requiresResources) append('R'); if (p.hasNativeSo || p.loadsNativeLibrary) append('N'); if (p.extendsAppOnlyPlugin) append('P')
        }
        sb.appendLine("| ${p.internalName} | ${p.repo} | $f | ${p.missingClasses.size} | ${p.missingClassMembers.size} | ${p.missingMembersOnKnownClasses.size} |")
    }
    sb.appendLine()

    sb.appendLine("## Per-plugin missing symbols\n")
    sb.appendLine("Priority plugins (${o.priority.joinToString()}) list every signature; others list classes only (full data in JSON).\n")
    val ordered = ok.sortedWith(compareBy<PluginResult> { it.internalName !in o.priority }.thenBy { it.internalName })
    for (p in ordered) {
        sb.appendLine("### ${p.internalName}${if (p.internalName in o.priority) " (priority)" else ""}\n")
        sb.appendLine("v${p.version}, plugin class `${p.pluginClassName}`, ${p.internalClasses} own classes, repo ${p.repo}" +
            (if (p.nativeSoFiles.isNotEmpty()) ", native: ${p.nativeSoFiles.joinToString()}" else ""))
        sb.appendLine("\nMissing classes (${p.missingClasses.size}): " +
            if (p.missingClasses.isEmpty()) "none" else p.missingClasses.entries.joinToString(", ") { "`${dotted(it.key)}`" })
        if (p.internalName in o.priority && p.missingClassMembers.isNotEmpty()) {
            sb.appendLine("\nMissing signatures (${p.missingClassMembers.size}):")
            p.missingClassMembers.forEach { sb.appendLine("- `${prettyMember(it)}`") }
        }
        if (p.missingMembersOnKnownClasses.isNotEmpty() && p.internalName in o.priority) {
            sb.appendLine("\nKnown-class gaps:")
            p.missingMembersOnKnownClasses.forEach { sb.appendLine("- `${prettyMember(it)}`") }
        }
        sb.appendLine()
    }
    o.outMd.absoluteFile.parentFile.mkdirs()
    o.outMd.writeText(sb.toString())
}
