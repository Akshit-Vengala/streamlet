package cloudstream.desktop.harness

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.PluginConverter
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.storage.StorageManager
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

/**
 * Child-JVM entry: one plugin per process.
 * args: <cs3> <jarsDir> <runDir> <eventsFile> <stageTimeoutSec>
 * Appends one JSON [Event] per line to <eventsFile> (flushed immediately so a kill loses nothing).
 */
object ChildMain {
    private lateinit var out: FileWriter

    @Synchronized
    private fun emit(e: Event) {
        out.write(mapper.writeValueAsString(e)); out.write("\n"); out.flush()
    }

    private val shadowed = mapOf(
        "com.lagradost.cloudstream3.network.WebViewResolver" to "webview-desktop",
        "com.lagradost.cloudstream3.network.CloudflareKiller" to "webview-desktop",
        "com.lagradost.api.ContextHelper_jvmKt" to "android-shim",
    )

    /** Returns an error text if a shadowed class is not served by the shadowing module. */
    fun classpathProblems(): List<String> = shadowed.mapNotNull { (cls, want) ->
        val loc = runCatching { Class.forName(cls).protectionDomain.codeSource.location.toString() }.getOrElse { "MISSING ($it)" }
        if (!loc.contains(want)) "$cls loaded from $loc, expected a $want artifact on the classpath BEFORE library-jvm" else null
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val cs3 = File(args[0])
        val jarsDir = File(args[1])
        val runDir = File(args[2]).apply { mkdirs() }
        out = FileWriter(File(args[3]), true)
        val stageMs = args[4].toLong() * 1000
        try {
            runPlugin(cs3, jarsDir, runDir, stageMs)
        } catch (t: Throwable) {
            emit(Event("fatal", error = Classifier.describe(t).copy(bucket = Bucket.HARNESS.id)))
            exitProcess(3)
        }
        emit(Event("done"))
        out.close()
        exitProcess(0)
    }

    private fun runPlugin(cs3: File, jarsDir: File, runDir: File, stageMs: Long) {
        val problems = classpathProblems()
        if (problems.isNotEmpty()) {
            emit(Event("fatal", error = Classifier.synthetic("ClasspathOrder", problems.joinToString("; "), Bucket.HARNESS)))
            exitProcess(4)
        }
        val storage = StorageManager(File(runDir, "data"))
        ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs"))
        ShimRuntime.setDataDir(File(runDir, "files"))
        cloudstream.desktop.webview.AndroidWebViewSupport.install() // real browser behind android.webkit.WebView
        com.lagradost.cloudstream3.CommonActivity.activity = androidx.appcompat.app.AppCompatActivity() // headless Activity: some plugins refuse to run without one

        // ---- convert
        val converter = PluginConverter(jarsDir)
        val c0 = System.currentTimeMillis()
        val jar = try {
            converter.convert(cs3).also { emit(Event("convert", ok = true, ms = System.currentTimeMillis() - c0, list = converter.warnings.toList())) }
        } catch (t: Throwable) {
            emit(Event("convert", ok = false, ms = System.currentTimeMillis() - c0, error = Classifier.describe(t).copy(bucket = Bucket.DEX2JAR.id), list = converter.warnings.toList()))
            return
        }

        // ---- load
        val loader = PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() }))
        val l0 = System.currentTimeMillis()
        val res = try { loader.load(cs3.nameWithoutExtension, cs3, jar) } catch (t: Throwable) {
            emit(Event("load", ok = false, ms = System.currentTimeMillis() - l0, error = Classifier.describe(t))); return
        }
        when (res) {
            is LoadResult.Failed -> {
                emit(Event("load", ok = false, ms = System.currentTimeMillis() - l0, error = Classifier.describe(res.error, res.missingClasses)))
                emit(Event("hits", list = ShimRuntime.unimplementedHits().sorted()))
                return
            }
            is LoadResult.Ok -> emit(Event("load", ok = true, ms = System.currentTimeMillis() - l0,
                list = res.apis, detail = "settingsUi=${res.hasSettingsUi}"))
        }

        // ---- stages per provider
        val providers = APIHolder.allProviders.toList().filter { it.sourcePlugin == cs3.absolutePath }
        for (api in providers) exercise(api, stageMs)
        emit(Event("hits", list = ShimRuntime.unimplementedHits().sorted()))
    }

    private class StageSkip(val why: String) : RuntimeException(why)

    private fun <T> timed(ms: Long, block: suspend () -> T): T {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "harness-stage").apply { isDaemon = true } }
        val f = ex.submit<T> { runBlocking { block() } }
        try {
            return f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true); ex.shutdownNow()
            throw TimeoutException("stage exceeded ${ms / 1000}s")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            ex.shutdown()
        }
    }

    private inline fun stage(api: MainAPI, name: String, run: () -> String?): Boolean {
        emit(Event("start", provider = api.name, stage = name))
        val t0 = System.currentTimeMillis()
        try {
            val detail = run()
            emit(Event("stage", provider = api.name, stage = name, status = Status.PASS, ms = System.currentTimeMillis() - t0, detail = detail))
            return true
        } catch (s: StageSkip) {
            emit(Event("stage", provider = api.name, stage = name, status = Status.SKIP, ms = System.currentTimeMillis() - t0, detail = s.why))
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - t0
            if (t is NotImplementedError && Classifier.firstFrame(t)?.startsWith("com.lagradost.cloudstream3.MainAPI.") == true) {
                emit(Event("stage", provider = api.name, stage = name, status = Status.SKIP, ms = ms, detail = "not implemented by provider"))
            } else {
                val err = if (t is TimeoutException) Classifier.synthetic("java.util.concurrent.TimeoutException", t.message ?: "timeout", Bucket.TIMEOUT)
                else if (t is EmptyResult) Classifier.synthetic("EmptyResult", t.message ?: "empty", Bucket.NO_RESULT)
                else Classifier.describe(t)
                emit(Event("stage", provider = api.name, stage = name, status = Status.FAIL, ms = ms, error = err))
            }
        }
        return false
    }

    private class EmptyResult(msg: String) : RuntimeException(msg)

    private fun exercise(api: MainAPI, stageMs: Long) {
        var firstUrl: String? = null

        // getMainPage
        if (!api.hasMainPage) {
            emit(Event("stage", provider = api.name, stage = "getMainPage", status = Status.SKIP, detail = "hasMainPage=false"))
        } else {
            stage(api, "getMainPage") {
                val mp = api.mainPage.firstOrNull() ?: throw StageSkip("empty mainPage list")
                val r = timed(stageMs) { api.getMainPage(1, MainPageRequest(mp.name, mp.data, mp.horizontalImages)) }
                val items = r?.items?.flatMap { it.list }.orEmpty()
                if (items.isEmpty()) throw EmptyResult("getMainPage(\"${mp.name}\") returned ${if (r == null) "null" else "no items"}")
                firstUrl = items.first().url
                "${items.size} items in ${r!!.items.size} lists; first=\"${items.first().name.take(60)}\""
            }
        }

        // search
        var searchUrl: String? = null
        stage(api, "search") {
            val r = timed(stageMs) { api.search("the", 1) }
            val items = r?.items.orEmpty()
            if (items.isEmpty()) throw EmptyResult("search(\"the\") returned ${if (r == null) "null" else "no items"}")
            searchUrl = items.first().url
            "${items.size} results; first=\"${items.first().name.take(60)}\""
        }

        // load
        var resp: LoadResponse? = null
        stage(api, "load") {
            val url = searchUrl ?: firstUrl ?: throw StageSkip("no url from search/getMainPage")
            val r = timed(stageMs) { api.load(url) } ?: throw EmptyResult("load(url) returned null")
            resp = r
            "${r.javaClass.simpleName} \"${r.name.take(60)}\""
        }

        // loadLinks
        stage(api, "loadLinks") {
            val lr = resp ?: throw StageSkip("no LoadResponse")
            val data = firstData(lr) ?: throw EmptyResult("LoadResponse (${lr.javaClass.simpleName}) has no episode / dataUrl")
            val links = java.util.concurrent.CopyOnWriteArrayList<String>()
            val subs = java.util.concurrent.atomic.AtomicInteger()
            // Aggregators stream links as extractors finish and may keep slow sources running for minutes. The app shows
            // links as they arrive, so a timeout WITH links is a pass (noted), a timeout without any is a failure.
            var timedOut = false
            val ok = try {
                timed(stageMs * 2) { api.loadLinks(data, false, { subs.incrementAndGet() }, { links += it.url }) }
            } catch (e: TimeoutException) {
                if (links.isEmpty()) throw e
                timedOut = true; null
            }
            if (links.isEmpty()) throw EmptyResult("loadLinks returned $ok with 0 links ($subs subtitles)")
            "${links.size} links, $subs subs${if (timedOut) " (partial: still running at ${stageMs * 2 / 1000}s)" else ""}; e.g. ${links.first().take(80)}"
        }
    }

    fun firstData(r: LoadResponse): String? = when (r) {
        is MovieLoadResponse -> r.dataUrl
        is LiveStreamLoadResponse -> r.dataUrl
        is TvSeriesLoadResponse -> r.episodes.firstOrNull()?.data
        is AnimeLoadResponse -> r.episodes.values.firstNotNullOfOrNull { it.firstOrNull()?.data }
        else -> null
    }
}
