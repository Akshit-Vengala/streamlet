package cloudstream.desktop.contracts

import java.io.File

/** Operating system helpers shared by all modules (Windows 10/11, Linux; macOS best effort). */
object Os {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = name.startsWith("windows")
    val isMac: Boolean = name.startsWith("mac")
    val isLinux: Boolean = !isWindows && !isMac
}

/**
 * Per-user data root, `CloudStreamDesktop` under:
 *  - Windows: `%APPDATA%` (Roaming)
 *  - Linux:   `$XDG_DATA_HOME` or `~/.local/share`
 *  - macOS:   `~/Library/Application Support`
 * An existing pre-XDG `~/.cloudstream-desktop` keeps being used. `-Dcloudstream.data=<dir>` overrides everything.
 */
object AppDirs {
    val root: File by lazy {
        System.getProperty("cloudstream.data")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        val home = System.getProperty("user.home")
        val legacy = File(home, ".cloudstream-desktop")
        when {
            Os.isWindows -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "CloudStreamDesktop") } ?: legacy
            legacy.isDirectory -> legacy
            Os.isMac -> File(home, "Library/Application Support/CloudStreamDesktop")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "CloudStreamDesktop")
        }
    }
}
