package cloudstream.desktop.integration

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.PluginConverter
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.storage.StorageManager
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Browse-only probe: getMainPage, search(query) and load(first N hits), printing full stack traces on failure.
 * Runs plugin code and uses the network: run by hand.   args: <cs3 file> <work dir> <query> [hits]
 */
fun main(args: Array<String>) {
    val cs3 = File(args[0]); val work = File(args[1]).apply { mkdirs() }; val query = args[2]
    val hits = args.getOrNull(3)?.toInt() ?: 3
    cloudstream.desktop.webview.AndroidWebViewSupport.install()
    CommonActivity.activity = androidx.appcompat.app.AppCompatActivity()
    val storage = StorageManager(File(work, "data"))
    ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs")); ShimRuntime.setDataDir(File(work, "files"))
    val jar = PluginConverter(File(work, "jars")).convert(cs3)
    println("load: " + PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() })).load(cs3.nameWithoutExtension, cs3, jar))
    var rc = 0
    if (System.getenv("PROBE_ANILIST") != null) runBlocking {
        // Same call shape as a plugin's AniList API call: form `data`, JSON content-type headers.
        val t0 = System.currentTimeMillis()
        runCatching {
            app.post("https://graphql.anilist.co", headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                data = mapOf("query" to "{Media(id:20){title{romaji}}}"))
        }.onSuccess { println("direct anilist: ${it.code} ${it.text.take(200)} in ${System.currentTimeMillis() - t0} ms") }
         .onFailure { e ->
             println("direct anilist FAILED in ${System.currentTimeMillis() - t0} ms"); e.printStackTrace(System.out)
             var c = e.cause; while (c != null) { println("cause: $c"); c = c.cause }
         }
    }
    System.getenv("PROBE_FN")?.let { spec -> runBlocking {
        // PROBE_FN="<pkg>.<FileKt>#<function>#<string arg>": call a plugin suspend fun(String) directly.
        val (cn, mn, arg) = spec.split('#', limit = 3)
        val cls = APIHolder.allProviders.first().javaClass.classLoader.loadClass(cn)
        val m = cls.methods.first { it.name == mn && it.parameterCount == 2 }
        val t0 = System.currentTimeMillis()
        runCatching {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { c -> m.invoke(null, arg, c) }
        }.onSuccess { println("PROBE_FN ok in ${System.currentTimeMillis() - t0} ms: ${it.toString().take(300)}") }
         .onFailure { e -> println("PROBE_FN FAILED in ${System.currentTimeMillis() - t0} ms"); (if (e is java.lang.reflect.InvocationTargetException) e.cause!! else e).printStackTrace(System.out) }
    } }
    System.getenv("PROBE_PARSE")?.let { spec -> runBlocking {
        // PROBE_PARSE="<class>#<anilist graphql query>": POST to AniList, then parse with the app mapper, showing the real error.
        val (cn, q) = spec.split('#', limit = 2)
        val cls = APIHolder.allProviders.first().javaClass.classLoader.loadClass(cn)
        val text = app.post("https://graphql.anilist.co", headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            data = mapOf("query" to q)).text
        println("PROBE_PARSE body: ${text.take(300)}")
        runCatching { com.lagradost.cloudstream3.mapper.readValue(text, cls) }
            .onSuccess { println("PROBE_PARSE ok: ${it.toString().take(300)}") }
            .onFailure { e -> println("PROBE_PARSE FAILED"); e.printStackTrace(System.out) }
    } }
    System.getenv("PROBE_GET")?.let { url -> runBlocking {
        val t0 = System.currentTimeMillis()
        runCatching { app.get(url) }
            .onSuccess { println("PROBE_GET ${it.code} ${it.text.length} chars in ${System.currentTimeMillis() - t0} ms: ${it.text.take(150)}") }
            .onFailure { e -> println("PROBE_GET FAILED in ${System.currentTimeMillis() - t0} ms"); e.printStackTrace(System.out) }
    } }
    for (api in APIHolder.allProviders.toList()) runBlocking {
        println("== ${api.name}")
        api.mainPage.firstOrNull()?.let { mp ->
            runCatching { withTimeout(60_000) { api.getMainPage(1, MainPageRequest(mp.name, mp.data, mp.horizontalImages)) } }
                .onSuccess { r -> println("mainPage '${mp.name}': ${r?.items?.sumOf { it.list.size }} items") }
                .onFailure { rc = 1; println("mainPage FAILED:"); it.printStackTrace(System.out) }
        }
        val items = runCatching { withTimeout(60_000) { api.search(query, 1) }?.items.orEmpty() }
            .onFailure { rc = 1; println("search FAILED:"); it.printStackTrace(System.out) }.getOrDefault(emptyList())
        println("search '$query': ${items.size} results")
        for (h in items.take(hits)) {
            println("-- load ${h.name} ${h.url}")
            runCatching { withTimeout(60_000) { api.load(h.url) } }
                .onSuccess { r ->
                    val eps = when (r) {
                        is AnimeLoadResponse -> r.episodes.mapValues { it.value.size }.toString()
                        is TvSeriesLoadResponse -> "${r.episodes.size}"
                        else -> "-"
                    }
                    println("   OK ${r?.javaClass?.simpleName} '${r?.name}' episodes=$eps")
                }
                .onFailure { rc = 1; println("   FAILED:"); it.printStackTrace(System.out) }
        }
    }
    storage.close()
    kotlin.system.exitProcess(rc)
}
