package cloudstream.desktop.integration

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.PluginConverter
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.storage.StorageManager
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Coordinator integration smoke: converts + loads local .cs3 files through the real loader, shim, storage and
 * webview modules. Executes plugin code (opt-in tool, run by hand).
 *
 * args: <cs3 dir> <work dir> [--live] [--only A,B]
 */
fun main(args: Array<String>) {
    val cs3Dir = File(args[0])
    val work = File(args[1]).apply { mkdirs() }
    val live = "--live" in args
    val only = args.indexOf("--only").takeIf { it >= 0 }?.let { args[it + 1].split(',').toSet() }

    println("== classpath origin checks")
    fun origin(name: String) = runCatching {
        Class.forName(name).protectionDomain.codeSource.location.toString().substringAfterLast('/')
    }.getOrElse { "MISSING: $it" }
    println("WebViewResolver  <- ${origin("com.lagradost.cloudstream3.network.WebViewResolver")}")
    println("CloudflareKiller <- ${origin("com.lagradost.cloudstream3.network.CloudflareKiller")}")
    println("ContextHelper    <- ${origin("com.lagradost.api.ContextHelper_jvmKt")}")
    println("Plugin           <- ${origin("com.lagradost.cloudstream3.plugins.Plugin")}")

    val storage = StorageManager(File(work, "data"))
    ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs"))
    ShimRuntime.setDataDir(File(work, "files"))
    cloudstream.desktop.webview.AndroidWebViewSupport.install() // real browser behind android.webkit.WebView

    val converter = PluginConverter(File(work, "jars"))
    val loader = PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() }))

    val files = cs3Dir.listFiles { f -> f.extension == "cs3" }!!.sortedBy { it.name }
        .filter { only == null || it.nameWithoutExtension in only }
    for (cs3 in files) {
        val name = cs3.nameWithoutExtension
        println("\n== $name")
        val t0 = System.currentTimeMillis()
        val jar = runCatching { converter.convert(cs3) }.getOrElse { println("  CONVERT FAIL: $it"); null } ?: continue
        println("  converted ${jar.length() / 1024} KB in ${System.currentTimeMillis() - t0} ms; warnings=${converter.warnings.size}")
        when (val r = loader.load(name, cs3, jar)) {
            is LoadResult.Ok -> println("  LOAD OK apis=${r.apis} settingsUi=${r.hasSettingsUi}")
            is LoadResult.Failed -> println("  LOAD FAILED ${r.error}\n    missing=${r.missingClasses.take(10)}")
        }
    }
    println("\n== eager class-define check (ClassFormatError / VerifyError / missing symbols)")
    for (lp in loader.loadedPlugins()) {
        val bad = java.util.TreeMap<String, Int>()
        var ok = 0
        val jar = File(work, "jars").listFiles { f -> f.extension == "jar" }!!
            .first { jarHasClass(it, lp.instance.javaClass.name) }
        java.util.zip.ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (!e.name.endsWith(".class")) continue
                val cn = e.name.removeSuffix(".class").replace('/', '.')
                try { Class.forName(cn, false, lp.classLoader).declaredMethods; ok++ }
                catch (t: Throwable) { bad.merge("${t::class.simpleName}: ${t.message?.take(110)}", 1, Int::plus) }
            }
        }
        println("  ${lp.manifest.name}: defined=$ok failed=${bad.values.sum()}")
        bad.entries.sortedByDescending { it.value }.take(8).forEach { println("    x${it.value} ${it.key}") }
    }

    println("\n== registered providers: ${APIHolder.allProviders.size}")
    if (live) {
        for (api in APIHolder.allProviders.toList()) {
            // Providers implement either search(query) or the paginated search(query, page); the app calls the latter.
            val res = runCatching { runBlocking { withTimeout(45_000) { api.search("the", 1) } } }
            println("  search ${api.name}: " + res.fold(
                { "${it?.items?.size ?: 0} results" + (it?.items?.firstOrNull()?.let { r -> " e.g. \"${r.name}\" ${r.url.take(60)}" } ?: "") },
                { "ERR ${it::class.simpleName}: ${it.message?.take(160)}" }))
        }
    }
    println("\n== shim stub hits (unimplemented): ${ShimRuntime.unimplementedHits().size}")
    ShimRuntime.unimplementedHits().sorted().take(40).forEach { println("  $it") }
    storage.close()
    kotlin.system.exitProcess(0)
}

private fun jarHasClass(jar: File, cls: String) =
    java.util.zip.ZipFile(jar).use { it.getEntry(cls.replace('.', '/') + ".class") != null }
