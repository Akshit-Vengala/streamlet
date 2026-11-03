package cloudstream.desktop.player

import cloudstream.desktop.contracts.Os
import java.io.File

/**
 * Finds mpv binaries without bundling them.
 *  - Windows: libmpv-2.dll / mpv.exe next to the app, in player/native, or on PATH; VLC in Program Files.
 *  - Linux:   libmpv.so.2 from the distro (apt install libmpv2 / dnf install mpv-libs), mpv / vlc on PATH.
 */
object MpvLocator {
    private val libNames = if (Os.isWindows) listOf("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll")
        else if (Os.isMac) listOf("libmpv.2.dylib", "libmpv.dylib") else listOf("libmpv.so.2", "libmpv.so", "libmpv.so.1")
    private val exeNames = if (Os.isWindows) listOf("mpv.exe") else listOf("mpv")
    private val vlcNames = if (Os.isWindows) listOf("vlc.exe") else listOf("vlc")

    private fun candidates(envVar: String, prop: String, names: List<String>, extraDirs: List<String> = emptyList()): List<File> {
        val out = ArrayList<File>()
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { out += File(it) }
        System.getProperty(prop)?.takeIf { it.isNotBlank() }?.let { out += File(it) }
        // Packaged app: jpackage puts resources under <app>/lib/app/resources (compose.desktop appResourcesRootDir).
        val dirs = listOfNotNull(
            System.getProperty("compose.application.resources.dir"), "player/native", "native", System.getProperty("user.dir"),
        ).map(::File) + extraDirs.map(::File)
        for (d in dirs) for (n in names) out += File(d, n)
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { d -> for (n in names) out += File(d, n) }
        return out
    }

    private val linuxLibDirs = listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib", "/usr/lib/aarch64-linux-gnu")

    /** Explicit libmpv file if one is found; null means "let the system loader try" (works on Linux with libmpv installed). */
    fun findDll(): File? =
        candidates("CLOUDSTREAM_MPV_DLL", "cloudstream.mpv.dll", libNames, if (Os.isLinux) linuxLibDirs else emptyList()).firstOrNull { it.isFile }

    /** Names to hand the system loader when no file was found ("mpv" -> libmpv.so via ld.so on Linux). */
    val systemLibNames: List<String> = if (Os.isWindows) listOf("libmpv-2", "mpv-2") else listOf("mpv")

    fun findExe(): File? = candidates("CLOUDSTREAM_MPV_EXE", "cloudstream.mpv.exe", exeNames, if (Os.isLinux) listOf("/usr/bin", "/usr/local/bin", "/snap/bin") else emptyList())
        .firstOrNull { it.isFile && it.canExecute() }

    fun findVlc(): File? {
        val fixed = if (Os.isWindows) listOf("C:/Program Files/VideoLAN/VLC/vlc.exe", "C:/Program Files (x86)/VideoLAN/VLC/vlc.exe").map(::File) else emptyList()
        val found = candidates("CLOUDSTREAM_VLC_EXE", "cloudstream.vlc.exe", vlcNames, if (Os.isLinux) listOf("/usr/bin", "/snap/bin") else emptyList())
        return (fixed + found).firstOrNull { it.isFile }
    }

    /** Install hint for the current OS (shown when no player backend is available). */
    val installHint: String = when {
        Os.isWindows -> "Put libmpv-2.dll next to the app (or in player/native, or set CLOUDSTREAM_MPV_DLL), or install mpv / VLC."
        Os.isMac -> "brew install mpv (or set CLOUDSTREAM_MPV_DLL to libmpv.dylib)."
        else -> "Install libmpv: sudo apt install libmpv2 (Debian/Ubuntu), sudo dnf install mpv-libs (Fedora), sudo pacman -S mpv (Arch); or set CLOUDSTREAM_MPV_DLL."
    }
}
