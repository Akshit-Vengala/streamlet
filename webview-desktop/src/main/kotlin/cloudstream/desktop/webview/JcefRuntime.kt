package cloudstream.desktop.webview

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import java.io.File

/** Progress of the first-run CEF download/extract/init, for the app to display. */
data class JcefProgress(val stage: Stage, val fraction: Float?) {
    enum class Stage { LOCATING, DOWNLOADING, EXTRACTING, INSTALLING, INITIALIZING, READY }
}

/**
 * Owns the single process-wide [CefApp]. CEF (~200 MB) is downloaded by jcefmaven on first use into
 * [installDir] (default `%APPDATA%/CloudStreamDesktop/jcef`). Call [install] explicitly at an
 * appropriate time (with a progress callback) so the UI can show it; [app] calls it implicitly
 * with no callback if that never happened.
 */
object JcefRuntime {
    /** Chromium major of the pinned jcefmaven (143.0.14). Keep in sync with build.gradle.kts. */
    const val CHROME_MAJOR = 143
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36"

    @Volatile
    var installDir: File = defaultInstallDir()

    /**
     * Browser profile (cookies, cache). Chromium allows ONE process per profile (a second one dies with
     * "profile in use", exit code 21, and CEF then hangs or crashes). The first process gets the persistent profile;
     * any other process running at the same time (e.g. parallel harness children) gets a throw-away one.
     */
    val profileDir: File by lazy { acquireProfile() }

    private var profileLock: java.nio.channels.FileLock? = null

    private fun acquireProfile(): File {
        val main = File(installDir.parentFile, "jcef-profile")
        main.mkdirs()
        try {
            val ch = java.nio.channels.FileChannel.open(
                File(main, ".cloudstream.lock").toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
            )
            val lock = ch.tryLock()
            if (lock != null) { profileLock = lock; return main }
            ch.close()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
        } catch (_: java.io.IOException) {
        }
        val temp = File(installDir.parentFile, "jcef-profile-tmp-${ProcessHandle.current().pid()}")
        temp.mkdirs()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { temp.deleteRecursively() } })
        return temp
    }

    /** `<AppDirs.root>/jcef`: %APPDATA% on Windows, XDG data dir on Linux. */
    fun defaultInstallDir(): File = File(cloudstream.desktop.contracts.AppDirs.root, "jcef")

    /** True when the CEF binaries are already on disk (no download needed). */
    fun isInstalled(): Boolean = File(installDir, "install.lock").exists()

    @Volatile
    private var cefApp: CefApp? = null

    /**
     * Downloads (if needed) and initializes CEF. Idempotent and thread-safe; blocks until done.
     * [onProgress] is called from the installing thread.
     */
    @Synchronized
    fun install(onProgress: (JcefProgress) -> Unit = {}): CefApp {
        cefApp?.let { onProgress(JcefProgress(JcefProgress.Stage.READY, 1f)); return it }
        installDir.mkdirs()
        profileDir.mkdirs()
        val builder = CefAppBuilder()
        builder.setInstallDir(installDir)
        builder.setProgressHandler { state, percent ->
            val frac = percent.takeIf { it >= 0f }?.let { it / 100f }
            val stage = when (state) {
                EnumProgress.LOCATING -> JcefProgress.Stage.LOCATING
                EnumProgress.DOWNLOADING -> JcefProgress.Stage.DOWNLOADING
                EnumProgress.EXTRACTING -> JcefProgress.Stage.EXTRACTING
                EnumProgress.INSTALL -> JcefProgress.Stage.INSTALLING
                EnumProgress.INITIALIZING -> JcefProgress.Stage.INITIALIZING
                EnumProgress.INITIALIZED -> JcefProgress.Stage.READY
                else -> JcefProgress.Stage.INSTALLING
            }
            onProgress(JcefProgress(stage, frac))
        }
        builder.cefSettings.apply {
            windowless_rendering_enabled = true
            cache_path = profileDir.absolutePath
            persist_session_cookies = true
            // Pin the UA so it matches the sec-ch-ua client hints (major = pinned CEF) and what we hand to NiceHttp.
            user_agent = DEFAULT_USER_AGENT
            log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
            log_file = File(profileDir, "cef.log").absolutePath
        }
        // Looks like a normal desktop Chrome to Cloudflare.
        builder.addJcefArgs("--disable-blink-features=AutomationControlled")
        // Pages run in hidden (off-screen rendered) or off-screen windows: without these, Chromium throttles timers and
        // animation frames there, and Cloudflare's automatic "Just a moment..." check never completes on its own.
        builder.addJcefArgs(
            "--disable-background-timer-throttling", "--disable-renderer-backgrounding",
            "--disable-backgrounding-occluded-windows", "--disable-features=CalculateNativeWinOcclusion",
        )
        // Diagnostics: extra Chromium switches, e.g. -Dcloudstream.jcef.args="--disable-gpu".
        System.getProperty("cloudstream.jcef.args")?.split(' ')?.filter { it.isNotBlank() }?.let { extra ->
            if (extra.isNotEmpty()) builder.addJcefArgs(*extra.toTypedArray())
        }
        val app = builder.build()
        cefApp = app
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { app.dispose() } })
        return app
    }

    fun app(): CefApp = cefApp ?: install()
}
