package cloudstream.desktop.app.update

import cloudstream.desktop.app.sync.Http
import cloudstream.desktop.app.sync.a
import cloudstream.desktop.app.sync.s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Version and update source baked in at build time (desktop-app/build.gradle.kts, gradle.properties). */
object BuildInfo {
    private val props = java.util.Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/app-build.properties")?.use { load(it) }
    }
    val version: String = props.getProperty("version")?.takeIf { it.isNotBlank() } ?: "dev"
    /** GitHub OWNER/NAME whose Releases are checked; blank = updates off. */
    val updateRepo: String = props.getProperty("updateRepo").orEmpty().trim()
}

data class AppRelease(
    val version: String, val tag: String, val notes: String, val pageUrl: String,
    val assetUrl: String?, val assetName: String?, val assetSize: Long,
)

sealed interface UpdateState {
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float?) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** "1.2.10" > "1.2.9"; a leading "v" and anything after "-"/"+" are ignored; missing parts count as 0. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(candidate); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * The installer to download for this OS and CPU from a release's asset names (null = none fits; the page is offered
 * instead). Package type preference first; within it, a name for this CPU beats a neutral one, and one built for
 * another CPU (arm64 on x64 or the other way round) is never picked.
 */
fun pickInstaller(
    names: List<String>, windows: Boolean, arch: String = System.getProperty("os.arch").orEmpty(),
    linuxPackage: String? = detectLinuxPackage(),
): String? {
    // Linux: the system's own package type first (a .deb is useless on Fedora), then the other, then an AppImage.
    val prefs = if (windows) listOf(".msi", ".exe") else when (linuxPackage) {
        ".rpm" -> listOf(".rpm", ".appimage", ".deb")
        ".deb" -> listOf(".deb", ".appimage", ".rpm")
        else -> listOf(".appimage", ".deb", ".rpm")
    }
    val arm = arch.lowercase().let { it.contains("aarch64") || it.contains("arm") }
    val mine = if (arm) listOf("arm64", "aarch64") else listOf("x64", "amd64", "x86_64", "win64")
    val other = if (arm) listOf("x64", "amd64", "x86_64", "win64") else listOf("arm64", "aarch64", "armv7", "armhf")
    for (ext in prefs) {
        val fits = names.filter { it.lowercase().endsWith(ext) }.filter { n -> other.none { n.lowercase().contains(it) } }
        fits.firstOrNull { n -> mine.any { n.lowercase().contains(it) } }?.let { return it }
        fits.firstOrNull()?.let { return it }
    }
    return null
}

/** ".deb" (Debian/Ubuntu/Mint: dpkg), ".rpm" (Fedora/openSUSE/RHEL: rpm without dpkg), else null. */
fun detectLinuxPackage(): String? {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
    fun has(vararg p: String) = p.any { File(it).exists() }
    return when {
        has("/usr/bin/dpkg", "/bin/dpkg") -> ".deb"
        has("/usr/bin/rpm", "/bin/rpm") -> ".rpm"
        else -> null
    }
}

/** The terminal command that installs a downloaded Linux package (null for other files). */
fun linuxInstallCommand(file: File): String? {
    val p = "'" + file.absolutePath.replace("'", "'\\''") + "'"
    fun has(vararg c: String) = c.any { File(it).exists() }
    val n = file.name.lowercase()
    return when {
        n.endsWith(".deb") -> "sudo apt install $p"
        n.endsWith(".rpm") && has("/usr/bin/dnf", "/bin/dnf") -> "sudo dnf install $p"
        n.endsWith(".rpm") && has("/usr/bin/zypper") -> "sudo zypper install $p"
        n.endsWith(".rpm") -> "sudo rpm -U $p"
        else -> null
    }
}

/**
 * App self-updater on GitHub Releases: checks `releases/latest` of [repo], downloads the installer for this OS and
 * launches it (Windows: the MSI/EXE replaces the installed app; Linux: the package is opened with the system).
 */
class AppUpdater(
    private val scope: CoroutineScope,
    private val repo: String = BuildInfo.updateRepo,
    private val current: String = BuildInfo.version,
    private val downloadDir: File = File(cloudstream.desktop.contracts.AppDirs.root, "updates"),
) {
    private val _state = MutableStateFlow<UpdateState>(if (repo.isBlank()) UpdateState.NotConfigured else UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    init {
        val log = cloudstream.desktop.app.AppLog.get("updater")
        scope.launch {
            _state.collect { s ->
                when (s) {
                    is UpdateState.Available -> log.info("Update available: ${s.release.version} (${s.release.assetName})")
                    is UpdateState.UpToDate -> log.info("Up to date ($current)")
                    is UpdateState.Ready -> log.info("Update downloaded: ${s.file}")
                    is UpdateState.Failed -> log.warning("Updater: ${s.message}")
                    else -> {}
                }
            }
        }
    }
    val configured: Boolean get() = repo.isNotBlank()
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private var job: Job? = null

    /** Checks now; [onNewer] is called when a newer release exists. */
    fun check(onNewer: (AppRelease) -> Unit = {}): Job? {
        if (!configured || job?.isActive == true) return job
        _state.value = UpdateState.Checking
        return scope.launch {
            _state.value = try {
                val r = Http.get("https://api.github.com/repos/$repo/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
                when {
                    r.code == 404 -> UpdateState.Failed("No releases published yet")
                    !r.ok -> UpdateState.Failed("GitHub answered HTTP ${r.code}")
                    else -> {
                        val o = r.obj()
                        val tag = o.s("tag_name").orEmpty()
                        val assets = o.a("assets")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
                        val name = pickInstaller(assets.mapNotNull { it.s("name") }, windows)
                        val asset = assets.firstOrNull { it.s("name") == name }
                        val rel = AppRelease(
                            tag.removePrefix("v").removePrefix("V"), tag, o.s("body").orEmpty(),
                            o.s("html_url") ?: "https://github.com/$repo/releases", asset?.s("browser_download_url"), name,
                            asset?.get("size")?.asLong ?: 0L,
                        )
                        if (tag.isNotBlank() && isNewerVersion(tag, current)) { onNewer(rel); UpdateState.Available(rel) }
                        else UpdateState.UpToDate(System.currentTimeMillis())
                    }
                }
            } catch (t: Throwable) {
                UpdateState.Failed("Could not check: ${t.message ?: t::class.simpleName}")
            }
        }.also { job = it }
    }

    /** Downloads the release's installer (progress in [state]). */
    fun download(rel: AppRelease): Job? {
        val url = rel.assetUrl ?: run { _state.value = UpdateState.Failed("This release has no installer for your system", rel); return null }
        if (job?.isActive == true) return job
        _state.value = UpdateState.Downloading(rel, 0f)
        return scope.launch {
            _state.value = try {
                val f = withContext(Dispatchers.IO) {
                    downloadDir.mkdirs()
                    val target = File(downloadDir, rel.assetName ?: "update")
                    val part = File(downloadDir, target.name + ".part")
                    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
                    val resp = client.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", Http.UA).build(), HttpResponse.BodyHandlers.ofInputStream())
                    if (resp.statusCode() !in 200..299) error("download failed: HTTP ${resp.statusCode()}")
                    val total = resp.headers().firstValueAsLong("content-length").orElse(rel.assetSize).takeIf { it > 0 }
                    resp.body().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                            while (input.read(buf).also { n = it } >= 0) {
                                out.write(buf, 0, n); done += n
                                if (total != null) _state.value = UpdateState.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    target.delete(); if (!part.renameTo(target)) part.copyTo(target, overwrite = true).also { part.delete() }
                    target
                }
                UpdateState.Ready(rel, f)
            } catch (t: Throwable) {
                UpdateState.Failed("Download failed: ${t.message ?: t::class.simpleName}", rel)
            }
        }.also { job = it }
    }

    /**
     * Starts the downloaded installer. Returns true when the app should now quit (Windows: the installer replaces the
     * running app's files). On Linux the package is handed to the system (software center / file manager).
     */
    fun install(file: File): Boolean {
        val n = file.name.lowercase()
        return when {
            windows && n.endsWith(".msi") -> { ProcessBuilder("msiexec", "/i", file.absolutePath).start(); true }
            windows && n.endsWith(".exe") -> { ProcessBuilder(file.absolutePath).start(); true }
            n.endsWith(".appimage") -> { file.setExecutable(true); ProcessBuilder(file.absolutePath).start(); true }
            else -> { cloudstream.desktop.app.openPath(file); false }
        }
    }
}
