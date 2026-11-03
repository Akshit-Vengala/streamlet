package cloudstream.desktop.harness

import cloudstream.desktop.loader.JdkHttpFetcher
import cloudstream.desktop.loader.RepoClient
import cloudstream.desktop.loader.sha256Hex
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

data class Options(
    val repos: List<String>,
    val work: File,
    val reports: File,
    val only: Set<String>?,
    val parallel: Int,
    val stageTimeoutSec: Long,
    val pluginTimeoutSec: Long,
    val heap: String,
    val knownBroken: File,
    val limit: Int?,
    val args: String,
)

fun parseOptions(args: Array<String>): Options {
    fun all(flag: String) = args.indices.filter { args[it] == flag }.map { args[it + 1] }
    fun one(flag: String) = all(flag).lastOrNull()
    val repos = all("--repo")
    Report.priorityNames = one("--priority")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    return Options(
        repos = repos,
        work = File(one("--work") ?: "tools/compat-harness/build/work").absoluteFile,
        reports = File(one("--out") ?: "reports").absoluteFile,
        only = one("--only")?.split(',')?.map { it.trim().lowercase() }?.toSet(),
        parallel = one("--parallel")?.toInt() ?: 2,
        stageTimeoutSec = one("--stage-timeout")?.toLong() ?: 45,
        pluginTimeoutSec = one("--plugin-timeout")?.toLong() ?: 600,
        heap = one("--heap") ?: "4g",
        knownBroken = File(one("--known-broken") ?: "tools/compat-harness/known-broken.json").absoluteFile,
        limit = one("--limit")?.toInt(),
        args = args.joinToString(" "),
    )
}

fun main(args: Array<String>) {
    if ("--help" in args) {
        println("""
            compat-harness (EXECUTES third-party plugin code, live network; never part of build/test)
              --repo <url>            repeatable, at least one (repo.json URL)
              --priority A,B          extensions listed first and in full
              --only A,B              internalName/name filter (case-insensitive)
              --limit N               first N plugins only (smoke)
              --parallel N            concurrent child JVMs (default 2)
              --stage-timeout SEC     per stage (default 45; loadLinks gets 2x)
              --plugin-timeout SEC    hard wall clock per plugin incl. conversion (default 600)
              --heap 4g               child -Xmx (big extensions need >= 3g to convert)
              --work DIR              downloads/jars/run dirs (default tools/compat-harness/build/work)
              --out DIR               report dir (default reports)
              --known-broken FILE     JSON {"Name": "why"} (default tools/compat-harness/known-broken.json)
        """.trimIndent())
        return
    }
    val opt = parseOptions(args)
    if (opt.repos.isEmpty()) { System.err.println("Give at least one --repo <url> (see --help)"); exitProcess(2) }
    val problems = ChildMain.classpathProblems()
    if (problems.isNotEmpty()) {
        System.err.println("FATAL classpath order wrong:\n  " + problems.joinToString("\n  "))
        exitProcess(2)
    }
    val run = execute(opt)
    exitProcess(if (run.plugins.isEmpty()) 1 else 0)
}

private data class Target(val ref: PluginRef, val url: String, val hash: String?, val status: Int, val repoDir: String)

fun execute(opt: Options): Run {
    val started = Instant.now()
    val runId = started.toString().replace(":", "").replace(".", "")
    val known: Map<String, String> = runCatching {
        if (opt.knownBroken.isFile) mapper.readValue(opt.knownBroken, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}) else emptyMap()
    }.getOrDefault(emptyMap())

    val http = JdkHttpFetcher()
    val client = RepoClient(http)
    val targets = ArrayList<Target>()
    runBlocking {
        for (repoUrl in opt.repos) {
            val repo = client.addRepo(repoUrl)
            val dir = sha256Hex(repoUrl.toByteArray()).take(12)
            val list = client.listAvailable(repo)
            println("repo ${repo.name}: ${list.size} plugins")
            list.distinctBy { it.internalName }.forEach {
                targets += Target(PluginRef(it.internalName, it.name, repoUrl, it.version, it.tvTypes), it.url, it.fileHash, it.status, dir)
            }
        }
    }
    var sel = targets.filter { t -> opt.only == null || t.ref.internalName.lowercase() in opt.only || t.ref.name.lowercase() in opt.only }
    sel = sel.sortedWith(compareBy({ !Report.isPriority(it.ref.name, it.ref.internalName) }, { !Report.isAnime(it.ref.tvTypes) }, { it.ref.name.lowercase() }))
    opt.limit?.let { sel = sel.take(it) }
    println("running ${sel.size} plugins, parallel=${opt.parallel}, stage timeout ${opt.stageTimeoutSec}s, plugin timeout ${opt.pluginTimeoutSec}s")

    val dl = File(opt.work, "downloads").apply { mkdirs() }
    val jars = File(opt.work, "jars").apply { mkdirs() }
    val runDir = File(opt.work, "runs/$runId").apply { mkdirs() }
    val pool = Executors.newFixedThreadPool(opt.parallel)
    val futures = sel.map { t ->
        pool.submit<PluginResult> { runOne(t, opt, http, dl, jars, runDir, known) }
    }
    val results = futures.map { it.get() }
    pool.shutdown()

    val prevFile = File(opt.reports, "compat-matrix.json")
    val prev = runCatching { mapper.readValue(prevFile, Run::class.java) }.getOrNull()
    val run = Run(started.toString(), opt.repos, results)
    // Partial runs (--only/--limit) must not clobber the full matrix: write them next to it.
    val partial = opt.only != null || opt.limit != null
    val base = if (partial) "compat-matrix.partial" else "compat-matrix"
    opt.reports.mkdirs()
    val prevForDiff = if (partial) null else prev
    File(opt.reports, "$base.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(run))
    File(opt.reports, "$base.md").writeText(Report.markdown(run, Report.diff(prevForDiff, run), "./gradlew --no-configuration-cache :tools:compat-harness:run --args=\"${opt.args}\""))
    println("wrote ${File(opt.reports, "$base.md")}")
    results.groupBy { it.bucket() ?: if (it.passed()) "pass" else "incomplete" }.forEach { (b, l) -> println("  $b: ${l.size}") }
    return run
}

private fun runOne(t: Target, opt: Options, http: JdkHttpFetcher, dl: File, jars: File, runDir: File, known: Map<String, String>): PluginResult {
    val ref = t.ref
    val kb = known[ref.internalName] ?: known[ref.name] ?: if (t.status == 0) "marked down (status=0) in repo manifest" else null
    val prio = Report.isPriority(ref.name, ref.internalName)
    val t0 = System.currentTimeMillis()
    fun fail(stage: String, e: ErrorInfo) = PluginResult(ref.internalName, ref.name, ref.repo, ref.version, prio, ref.tvTypes, kb,
        convert = StageResult(stage, Status.FAIL, System.currentTimeMillis() - t0, error = e, bucket = e.bucket), totalMs = System.currentTimeMillis() - t0)
    println("[start] ${ref.name}")

    // ---- download (cached, hash verified)
    val safe = ref.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val cs3 = File(File(dl, t.repoDir).apply { mkdirs() }, "$safe.cs3")
    val want = t.hash?.removePrefix("sha256-")?.lowercase()
    try {
        if (!(cs3.isFile && (want == null || sha256Hex(cs3) == want))) {
            val bytes = runBlocking { http.getBytes(t.url) }
            val got = sha256Hex(bytes)
            if (want != null && got != want) return fail("download", Classifier.synthetic("HashMismatch", "expected $want got $got", Bucket.EXTERNAL))
            cs3.writeBytes(bytes)
        }
    } catch (e: Throwable) {
        return fail("download", Classifier.describe(e).let { if (it.bucket == Bucket.CORE.id) it.copy(bucket = Bucket.EXTERNAL.id) else it })
    }

    // ---- child JVM
    val pdir = File(runDir, safe).apply { mkdirs() }
    val events = File(pdir, "events.jsonl")
    val log = File(pdir, "child.log")
    val argFile = File(pdir, "child.args")
    val cp = System.getProperty("java.class.path").replace('\\', '/')
    argFile.writeText("-cp\n\"$cp\"\n")
    val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").path
    val cmd = listOf(java, "-Xmx${opt.heap}", "-Dfile.encoding=UTF-8", "@${argFile.path}", ChildMain::class.java.name,
        cs3.path, jars.path, File(pdir, "work").path, events.path, opt.stageTimeoutSec.toString())
    val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log)
    // Sandbox %APPDATA% so JCEF natives / anything else keyed on it stays out of the user's profile.
    pb.environment()["APPDATA"] = File(opt.work, "appdata").apply { mkdirs() }.path
    val proc = pb.start()
    val finished = proc.waitFor(opt.pluginTimeoutSec, TimeUnit.SECONDS)
    if (!finished) killTree(proc)
    val exit = if (finished) proc.exitValue() else null
    val lines = if (events.isFile) events.readLines() else emptyList()
    val tail = if (log.isFile) log.readText().takeLast(1500) else null
    val res = Results.build(ref, Results.parseEvents(lines), exit, !finished, System.currentTimeMillis() - t0, tail, kb, prio)
    println("[done ] ${ref.name}: ${res.bucket() ?: if (res.passed()) "pass" else "incomplete"} (${res.totalMs / 1000}s)")
    return res
}

/** Kill the child and all its descendants (plugins may spawn helper processes). */
fun killTree(p: Process) {
    val h = p.toHandle()
    h.descendants().forEach { it.destroyForcibly() }
    h.destroyForcibly()
    p.waitFor(10, TimeUnit.SECONDS)
}
