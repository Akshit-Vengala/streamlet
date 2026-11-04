package cloudstream.desktop.app

import cloudstream.desktop.contracts.AppDirs
import java.io.File

/**
 * Start-up housekeeping (in the background): removes what earlier runs left behind and nothing needs.
 *  - jcef-profile-tmp-<pid>: the browser profile a second running copy of the app gets; deleted once that copy is gone.
 *  - Chromium's auto-downloaded components (DRM, text-to-speech, suggestion models): the background browser only
 *    solves Cloudflare checks and loads extension pages. Component updates are off (JcefRuntime), so they stay gone.
 *  - subtitle downloads older than a day, downloaded app updates older than a week, the old errors.log.
 *  - the artwork disk cache is trimmed to its budget.
 */
object DiskCleanup {
    private val log = AppLog.get("cleanup")

    fun run() {
        val root = AppDirs.root
        var freed = 0L
        fun delete(f: File) { if (!f.exists()) return; val n = sizeOf(f); if (f.deleteRecursively()) freed += n }

        root.listFiles { f -> f.isDirectory && f.name.startsWith("jcef-profile-tmp-") }?.forEach { dir ->
            val pid = dir.name.removePrefix("jcef-profile-tmp-").toLongOrNull()
            val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) delete(dir)
        }
        val profile = File(root, "jcef-profile")
        for (name in listOf("component_crx_cache", "WasmTtsEngine", "WidevineCdm", "OnDeviceHeadSuggestModel", "OptimizationGuidePredictionModels",
            "extensions_crx_cache", "Crashpad/reports", "GrShaderCache", "ShaderCache")) delete(File(profile, name))

        val day = 24 * 3600_000L
        val now = System.currentTimeMillis()
        File(System.getProperty("java.io.tmpdir"), "streamlet-subs").listFiles()?.forEach { if (now - it.lastModified() > day) delete(it) }
        File(root, "updates").listFiles()?.forEach { if (now - it.lastModified() > 7 * day) delete(it) }
        delete(File(root, "logs/errors.log"))
        // Side logs of a second copy (streamlet.0.log.1 ...) once nothing writes them.
        File(root, "logs").listFiles { f -> f.name.matches(Regex("streamlet\\.\\d+\\.log\\.\\d+")) && !File(f.path + ".lck").exists() }?.forEach { delete(it) }

        val before = sizeOf(cloudstream.desktop.app.ui.ImageCache.diskDir)
        cloudstream.desktop.app.ui.ImageCache.trimDisk()
        freed += before - sizeOf(cloudstream.desktop.app.ui.ImageCache.diskDir)
        if (freed > 0) log.info("Freed ${freed / (1 shl 20)} MB of leftovers on disk")
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
