package cloudstream.desktop.loader

import cloudstream.desktop.contracts.LoadResult
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * Calls the plugin's entrypoint. The default calls `Plugin.load(Context)` (Track C's shim class) when the
 * plugin extends it and a context is available, else `BasePlugin.load()`.
 */
fun interface PluginEntrypoint {
    fun invoke(plugin: BasePlugin, manifest: Cs3Manifest)
}

/** Supplies the shim `android.content.Context` instance (typed as Any to avoid a compile dependency on C). */
fun interface ContextProvider {
    fun context(): Any?
}

class DefaultEntrypoint(private val contextProvider: ContextProvider? = null) : PluginEntrypoint {
    override fun invoke(plugin: BasePlugin, manifest: Cs3Manifest) {
        val appPluginCls = generateSequence<Class<*>>(plugin.javaClass) { it.superclass }
            .firstOrNull { it.name == APP_PLUGIN_CLASS }
        if (appPluginCls == null) { plugin.load(); return }
        val ctx = contextProvider?.context()
            ?: error("Plugin extends $APP_PLUGIN_CLASS but no Context shim is configured")
        val m = appPluginCls.methods.first { it.name == "load" && it.parameterCount == 1 }
        m.invoke(plugin, ctx)
    }

    companion object { const val APP_PLUGIN_CLASS = "com.lagradost.cloudstream3.plugins.Plugin" }
}

class LoadedPlugin(
    val file: File,
    val manifest: Cs3Manifest,
    val instance: BasePlugin,
    val classLoader: URLClassLoader,
    val apis: List<String>,
    val hasSettingsUi: Boolean,
)

/**
 * One URLClassLoader per plugin; parent = the app class loader (core + shim + webview).
 * Mirrors upstream PluginManager.loadPlugin/unloadPlugin.
 */
class PluginLoader(
    private val parent: ClassLoader = PluginLoader::class.java.classLoader,
    private val entrypoint: PluginEntrypoint = DefaultEntrypoint(),
    /** Extra cleanup on unload, e.g. shim registries keyed by plugin filename. */
    private val onUnload: (filename: String) -> Unit = {},
    /** Safety check before any extension code is loaded (null = none, e.g. tests with local fixtures). */
    val guard: PluginGuard? = null,
) {
    private val loaded = LinkedHashMap<String, LoadedPlugin>()

    @Synchronized fun loadedPlugins(): List<LoadedPlugin> = loaded.values.toList()

    @Synchronized fun isLoaded(cs3: File) = loaded.containsKey(cs3.absolutePath)

    /** [cs3] is used as the plugin's `filename` (key for `sourcePlugin`), [jar] is the converted classes. */
    @Synchronized
    fun load(displayName: String, cs3: File, jar: File): LoadResult {
        val t0 = System.nanoTime()
        val r = loadUnlogged(displayName, cs3, jar)
        val ms = (System.nanoTime() - t0) / 1_000_000
        when (r) {
            is LoadResult.Ok -> log.info("Loaded extension ${r.name} in $ms ms: ${r.apis.size} provider(s)/extractor(s)")
            is LoadResult.Failed -> log.warning("Extension ${r.name} not loaded" +
                (if (r.blocked.isNotEmpty()) " (blocked by the safety check: ${r.blocked})" else "") +
                ": ${r.error.take(500)}" + (if (r.missingClasses.isNotEmpty()) " missing=${r.missingClasses.take(10)}" else ""))
        }
        return r
    }

    private val log = java.util.logging.Logger.getLogger("streamlet.extensions")

    private fun loadUnlogged(displayName: String, cs3: File, jar: File): LoadResult {
        val key = cs3.absolutePath
        if (loaded.containsKey(key)) unload(cs3)
        var cl: URLClassLoader? = null
        var instance: BasePlugin? = null
        return try {
            val manifest = readCs3Manifest(cs3)
            val className = manifest.pluginClassName ?: error("manifest.json has no pluginClassName")
            // Safety: nothing of the extension runs before its bytecode has been checked.
            val g = guard
            val loaderParent = if (g == null) parent else {
                val gk = PluginGuard.keyOf(cs3)
                val blocked = g.blockedFor(gk, jar)
                if (blocked.isNotEmpty()) {
                    val e = BlockedExtensionException(manifest.name ?: displayName, gk, blocked, g.report(jar))
                    return LoadResult.Failed(manifest.name ?: displayName, e.message!!, emptyList(), blocked.map { it.name })
                }
                GuardedParentLoader(parent, g.allowed(gk))
            }
            cl = URLClassLoader(arrayOf(jar.toURI().toURL()), loaderParent)
            val cls = Class.forName(className, true, cl)
            instance = cls.getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = key
            entrypoint.invoke(instance, manifest)
            val apis = apisOf(key)
            val lp = LoadedPlugin(cs3, manifest, instance, cl, apis, detectSettingsUi(instance))
            loaded[key] = lp
            LoadResult.Ok(manifest.name ?: displayName, apis, lp.hasSettingsUi)
        } catch (t: Throwable) {
            // Roll back anything the half-loaded plugin registered.
            runCatching { removeRegistrations(key) }
            runCatching { cl?.close() }
            failure(displayName, t)
        }
    }

    @Synchronized
    fun unload(cs3: File): Boolean {
        val key = cs3.absolutePath
        val lp = loaded.remove(key) ?: return false
        runCatching { lp.instance.beforeUnload() }
        removeRegistrations(key)
        runCatching { onUnload(key) }
        runCatching { lp.classLoader.close() }
        return true
    }

    @Synchronized fun unloadAll() { loaded.values.map { it.file }.forEach { unload(it) } }

    private fun apisOf(key: String): List<String> =
        APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.sourcePlugin == key }.map { it.name } } +
            extractorApis.withLock { extractorApis.filter { it.sourcePlugin == key }.map { "extractor:${it.name}" } }

    private fun removeRegistrations(key: String) {
        APIHolder.apis.filter { it.sourcePlugin == key }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { p: MainAPI -> p.sourcePlugin == key } }
        extractorApis.withLock { extractorApis.removeAll { e: ExtractorApi -> e.sourcePlugin == key } }
    }

    private fun detectSettingsUi(p: BasePlugin): Boolean = runCatching {
        p.javaClass.methods.firstOrNull { it.name == "getOpenSettings" && it.parameterCount == 0 }?.invoke(p) != null
    }.getOrDefault(false)

    companion object {
        /** Extracts internal class names (dotted) from linkage errors anywhere in the cause chain. */
        fun missingClasses(t: Throwable): List<String> {
            val out = LinkedHashSet<String>()
            var cur: Throwable? = t
            var guard = 0
            while (cur != null && guard++ < 20) {
                when (cur) {
                    is NoClassDefFoundError -> cur.message?.let { out += it.substringBefore(" (").replace('/', '.') }
                    is ClassNotFoundException -> cur.message?.let { out += it.replace('/', '.') }
                    is NoSuchMethodError, is NoSuchFieldError, is AbstractMethodError, is IncompatibleClassChangeError ->
                        cur.message?.let { out += "symbol:" + it.replace('/', '.') }
                }
                cur = if (cur is InvocationTargetException) cur.targetException else cur.cause
            }
            return out.toList()
        }

        fun failure(name: String, t: Throwable): LoadResult.Failed {
            var root: Throwable = t
            while (true) {
                val next = (if (root is InvocationTargetException) root.targetException else root.cause) ?: break
                if (next === root) break
                root = next
            }
            return LoadResult.Failed(name, "${root.javaClass.name}: ${root.message}", missingClasses(t))
        }
    }
}
