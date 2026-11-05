package cloudstream.desktop.app

import cloudstream.desktop.app.update.AppUpdater
import cloudstream.desktop.app.update.UpdateState
import cloudstream.desktop.app.update.isNewerVersion
import cloudstream.desktop.app.update.pickInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterTest {
    @Test fun `release tags compare numerically`() {
        assertTrue(isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.2.10", "1.2.9"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertFalse(isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0-beta", "1.0.0"))
        assertFalse(isNewerVersion("0.9.9", "1.0.0"))
    }

    @Test fun `the installer for this OS is picked from the release assets`() {
        val assets = listOf("App-1.0.1.deb", "App-1.0.1.exe", "App-1.0.1.msi", "App-1.0.1-x86_64.AppImage", "checksums.txt")
        assertEquals("App-1.0.1.msi", pickInstaller(assets, windows = true, arch = "amd64"))
        assertEquals("App-1.0.1-x86_64.AppImage", pickInstaller(assets, windows = false, arch = "amd64", linuxPackage = null))
        // Linux: the system's own package type wins (a .deb can't be installed on Fedora).
        val linux = listOf("Streamlet-1.0.1.deb", "Streamlet-1.0.1-1.x86_64.rpm")
        assertEquals("Streamlet-1.0.1.deb", pickInstaller(linux, windows = false, arch = "amd64", linuxPackage = ".deb"))
        assertEquals("Streamlet-1.0.1-1.x86_64.rpm", pickInstaller(linux, windows = false, arch = "amd64", linuxPackage = ".rpm"))
        assertEquals("Streamlet-1.0.1.deb", pickInstaller(listOf("Streamlet-1.0.1.deb"), windows = false, arch = "amd64", linuxPackage = ".rpm"))
        assertEquals("App.exe", pickInstaller(listOf("App.exe"), windows = true, arch = "amd64"))
        assertNull(pickInstaller(listOf("source.zip"), windows = true, arch = "amd64"))
        // Another CPU's build is never picked; this CPU's wins over a neutral name.
        val ps = listOf("PS-7.6.9-win-arm64.msi", "PS-7.6.9-win-x64.msi", "PS-7.6.9-win-x86.msi")
        assertEquals("PS-7.6.9-win-x64.msi", pickInstaller(ps, windows = true, arch = "amd64"))
        assertEquals("PS-7.6.9-win-arm64.msi", pickInstaller(ps, windows = true, arch = "aarch64"))
        assertNull(pickInstaller(listOf("App-arm64.msi"), windows = true, arch = "amd64"))
    }

    @Test fun `a finished download stays ready when the automatic check runs`() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        try {
            val msi = java.io.File(dir, "Streamlet-1.0.1.msi").apply { writeBytes(ByteArray(1234)) }
            val u = AppUpdater(CoroutineScope(Dispatchers.Unconfined), repo = "owner/name", current = "1.0.0", downloadDir = dir)
            val rel = cloudstream.desktop.app.update.AppRelease("1.0.1", "v1.0.1", "", "https://x", "https://x/Streamlet-1.0.1.msi", msi.name, msi.length())
            assertNull(u.download(rel), "already on disk at full size: no second download")
            assertTrue(u.state.value is UpdateState.Ready)
            assertNull(u.check(), "a check does not replace a downloaded update")
            assertEquals(msi, (u.state.value as UpdateState.Ready).file)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `without a release repository the updater is off`() {
        val u = AppUpdater(CoroutineScope(Dispatchers.Unconfined), repo = "", current = "1.0.0")
        assertFalse(u.configured)
        assertEquals(UpdateState.NotConfigured, u.state.value)
        assertNull(u.check())
    }
}
