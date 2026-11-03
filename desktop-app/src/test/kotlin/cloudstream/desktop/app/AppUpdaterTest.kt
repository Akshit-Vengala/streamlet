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
        assertEquals("App-1.0.1-x86_64.AppImage", pickInstaller(assets, windows = false, arch = "amd64"))
        assertEquals("App.exe", pickInstaller(listOf("App.exe"), windows = true, arch = "amd64"))
        assertNull(pickInstaller(listOf("source.zip"), windows = true, arch = "amd64"))
        // Another CPU's build is never picked; this CPU's wins over a neutral name.
        val ps = listOf("PS-7.6.9-win-arm64.msi", "PS-7.6.9-win-x64.msi", "PS-7.6.9-win-x86.msi")
        assertEquals("PS-7.6.9-win-x64.msi", pickInstaller(ps, windows = true, arch = "amd64"))
        assertEquals("PS-7.6.9-win-arm64.msi", pickInstaller(ps, windows = true, arch = "aarch64"))
        assertNull(pickInstaller(listOf("App-arm64.msi"), windows = true, arch = "amd64"))
    }

    @Test fun `without a release repository the updater is off`() {
        val u = AppUpdater(CoroutineScope(Dispatchers.Unconfined), repo = "", current = "1.0.0")
        assertFalse(u.configured)
        assertEquals(UpdateState.NotConfigured, u.state.value)
        assertNull(u.check())
    }
}
