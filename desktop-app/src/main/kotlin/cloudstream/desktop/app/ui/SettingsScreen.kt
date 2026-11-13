package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.AppSettings
import cloudstream.desktop.app.PlayerChoice

/** Settings categories (left rail), most used first. */
private enum class SettingsCategory(val label: String, val hint: String) {
    Player("Player", "Playback, seeking, subtitles"),
    Sources("Sources", "Fallback, hidden sources, priority"),
    Providers("Providers", "Which providers Home and Search use"),
    Accounts("Accounts", "Tracking and subtitle services"),
    Profiles("Profiles", "Profiles and data folder"),
    Shortcuts("Shortcuts", "Keyboard shortcuts"),
    About("About", "Version and app updates"),
}

@Composable
fun SettingsScreen(c: AppController) {
    var cat by rememberSaveable { mutableStateOf(SettingsCategory.Player) }
    Row(Modifier.fillMaxSize().screenPadding(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(230.dp).fillMaxHeight()) {
            ScreenTitle("Settings")
            SettingsCategory.entries.forEach { k ->
                val sel = k == cat
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { cat = k }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(k.label, color = Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                    Text(k.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(cat.label, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) }
            when (cat) {
                SettingsCategory.Player -> item { PlayerSection(c) }
                SettingsCategory.Sources -> item { SourcesSection(c) }
                SettingsCategory.Providers -> providersItems(c)
                SettingsCategory.Accounts -> item { AccountsSection(c) }
                SettingsCategory.Profiles -> {
                    item { ProfilesSection(c) }
                    item { DataSection(c) }
                }
                SettingsCategory.Shortcuts -> item { ShortcutsSection() }
                SettingsCategory.About -> item { AboutSection(c) }
            }
        }
    }
}

@Composable
private fun PlayerSection(c: AppController) {
    val st = c.services.settings
    val quality by st.defaultQuality.collectAsStateCompat()
    val lang by st.subtitleLang.collectAsStateCompat()
    val player by st.playerChoice.collectAsStateCompat()
    val back by st.seekBackSeconds.collectAsStateCompat()
    val fwd by st.seekForwardSeconds.collectAsStateCompat()
    val autoNext by st.autoNextEpisode.collectAsStateCompat()
    val autoSkip by st.autoSkipIntro.collectAsStateCompat()
    val steps = listOf(5, 10, 15, 20, 30, 45, 60, 90)
    SectionCard {
        Group("Playback")
        SettingRow("Player", "Built-in plays inside the app; the others open a separate window.") {
            Dropdown(player.label, PlayerChoice.entries.toList(), { it.label }) { st.setPlayerChoice(it) }
        }
        SettingRow("Preferred quality", "Sources closest to this (at or below it first) are tried first.") {
            Dropdown(AppSettings.qualityChoices.firstOrNull { it.first == quality }?.second ?: "${quality}p", AppSettings.qualityChoices, { it.second }) { st.setDefaultQuality(it.first) }
        }
        Spacer8()
        Group("Episodes")
        SettingRow("Play the next episode automatically", "A 5-second countdown at the end, which you can cancel.") { Switch(autoNext, { st.setAutoNextEpisode(it) }) }
        SettingRow("Skip intros automatically", "Uses the video's chapters, or AniSkip for anime. Otherwise a \"Skip intro\" button shows.") { Switch(autoSkip, { st.setAutoSkipIntro(it) }) }
        Spacer8()
        Group("Seeking")
        SettingRow("Rewind step", "Player button and Left arrow.") { Dropdown("$back s", steps, { "$it s" }) { st.setSeekBackSeconds(it) } }
        SettingRow("Fast-forward step", "Player button and Right arrow.") { Dropdown("$fwd s", steps, { "$it s" }) { st.setSeekForwardSeconds(it) } }
        Spacer8()
        Group("Subtitles")
        SettingRow("Preferred subtitle language", "Picked automatically when a source has no subtitle of its own.") {
            OutlinedTextField(lang, { st.setSubtitleLang(it) }, singleLine = true, modifier = Modifier.width(200.dp), placeholder = { Text("English") })
        }
    }
}

/** Fallback, hidden sources; the priority list itself is edited in the player (Source panel, gear). */
@Composable
private fun SourcesSection(c: AppController) {
    val st = c.services.settings
    val priority by st.sourcePriority.collectAsStateCompat()
    val hidden by st.hiddenSources.collectAsStateCompat()
    val fallback by st.autoFallback.collectAsStateCompat()
    val fallbackSec by st.fallbackSeconds.collectAsStateCompat()
    val raw by st.showRawSources.collectAsStateCompat()
    SectionCard {
        Group("When a source fails")
        SettingRow("Switch to the next source automatically", null) { Switch(fallback, { st.setAutoFallback(it) }) }
        SettingRow("Give up on a source with no video after", null) {
            Dropdown("$fallbackSec s", listOf(10, 15, 20, 30, 45, 60), { "$it s" }) { st.setFallbackSeconds(it) }
        }
        Spacer8()
        Group("Priority")
        Text(
            "Set it in the player: Source → ⚙ Priority. Ranked sources are tried first, in order; the rest follow by quality.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (priority.isEmpty()) Text("Nothing ranked yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(priority.withIndex().joinToString("   ") { (i, n) -> "#${i + 1} $n" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Spacer8()
        var hiddenOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(enabled = hidden.isNotEmpty()) { hiddenOpen = !hiddenOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Group("Hidden sources (${hidden.size})") }
            if (hidden.isNotEmpty()) Text(if (hiddenOpen) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Hidden sources (or single servers, e.g. \"Source [Server 1]\") are never listed or played. Hide them from the player: Source → ⊘ or right-click.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hidden.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (!hiddenOpen) Text("${hidden.size} hidden. Click the title to show them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(
            // Indented under the title with a guide line, so the entries read as part of "Hidden sources".
            Modifier.padding(start = 12.dp, top = 6.dp).drawBehind {
                drawRect(Color.White.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            }.padding(start = 14.dp),
        ) {
            hidden.sortedBy { it.lowercase() }.forEach { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f)); TextButton(onClick = { st.setSourceHidden(name, false) }) { Text("Unhide") }
                }
            }
        }
        Spacer8()
        Group("Advanced")
        SettingRow("Show raw sources", "Plugin order, every link, full URL / referer / headers.") { Switch(raw, { st.setShowRawSources(it) }) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.providersItems(c: AppController) {
    item {
        val rev by c.services.catalogRevision.collectAsStateCompat()
        val providers = remember(rev) { c.providers() }
        Text("${providers.size} loaded. Disabled providers are skipped by Home and Search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item {
        val st = c.services.settings
        val rev by c.services.catalogRevision.collectAsStateCompat()
        val providers = remember(rev) { c.providers() }
        val disabled by st.disabledProviders.collectAsStateCompat()
        SectionCard {
            providers.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name); Badge(p.lang)
                        Text(p.types.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(p.name !in disabled, { st.setProviderEnabled(p.name, it) })
                }
            }
        }
    }
}

@Composable
private fun DataSection(c: AppController) {
    SectionCard {
        Group("Data folder")
        Text(c.services.rootDir.absolutePath, style = MaterialTheme.typography.bodyMedium)
        Text("Settings, library, history, installed extensions and caches live here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer8()
        OutlinedButton(onClick = { cloudstream.desktop.app.openPath(c.services.rootDir) }) { Text("Open folder") }
        Spacer8()
        DownloadSettings(c)
        Spacer8()
        NetworkSettings(c)
    }
}

/** CloudStream's "DNS over HTTPS": pick a resolver, and test it. */
@Composable
private fun NetworkSettings(c: AppController) {
    val gs = c.services.globalSettings
    val dns by gs.dnsProvider.collectAsStateCompat()
    var test by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Group("Network")
    SettingRow("DNS over HTTPS", "Look sites up through this resolver instead of your provider's DNS, which gets past DNS blocking. Used by extensions and HLS downloads.") {
        Dropdown(dns.label, cloudstream.desktop.webview.DohProvider.entries.toList(), { it.label }) { gs.setDnsProvider(it); test = null }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = {
            test = "Testing…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val r = cloudstream.desktop.webview.NetworkDns.test(dns, "github.com")
                test = r.fold({ "${dns.label}: github.com → ${it.take(3).joinToString()}" }, { "${dns.label} failed: ${it.message ?: it.javaClass.simpleName}" })
            }
        }) { Text("Test") }
        test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DownloadSettings(c: AppController) {
    val gs = c.services.globalSettings
    val dir by gs.downloadDir.collectAsStateCompat()
    val max by gs.maxDownloads.collectAsStateCompat()
    val folder = dir?.let { java.io.File(it) } ?: cloudstream.desktop.app.defaultDownloadDir()
    Group("Downloads")
    Text(folder.absolutePath, style = MaterialTheme.typography.bodyMedium)
    Text("Episodes and their subtitles are saved here, one folder per title. Changing it does not move earlier downloads.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer8()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val ch = javax.swing.JFileChooser(folder.takeIf { it.isDirectory } ?: folder.parentFile).apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Download folder"
            }
            if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) gs.setDownloadDir(ch.selectedFile.absolutePath)
        }) { Text("Change…") }
        OutlinedButton(onClick = { folder.mkdirs(); cloudstream.desktop.app.openPath(folder) }) { Text("Open folder") }
        if (dir != null) TextButton(onClick = { gs.setDownloadDir(null) }) { Text("Use default") }
    }
    SettingRow("Downloads at the same time", null) { Dropdown("$max", listOf(1, 2, 3, 4, 5, 6, 8), { "$it" }) { gs.setMaxDownloads(it) } }
}

@Composable
private fun ShortcutsSection() {
    val app = listOf(
        "Ctrl + 1 … 5" to "Home / Search / My List / Extensions / Settings",
        "Ctrl + F" to "Search", "Alt + Left, Esc" to "Back", "Right-click a title" to "Watch status / remove",
    )
    val player = listOf(
        "Space" to "Play / pause", "Left / Right" to "Seek by the steps set in Player", "Up / Down, mouse wheel" to "Volume",
        "M" to "Mute", "F, double-click" to "Full screen", "[ ]  - +  =" to "Speed (= resets)", "V / B" to "Next subtitle / audio track",
        "G / H, J / K" to "Subtitle / audio delay", "N" to "Next episode", "F1 or ?" to "All player shortcuts",
    )
    SectionCard {
        Group("App")
        app.forEach { (k, v) -> KeyRow(k, v) }
        Spacer8()
        Group("Player")
        player.forEach { (k, v) -> KeyRow(k, v) }
    }
}

/** Version, and the app updater (GitHub Releases of the repo set at build time). */
@Composable
private fun AboutSection(c: AppController) {
    val up = c.updater
    val state by up.state.collectAsStateCompat()
    val gs = c.services.globalSettings
    val auto by gs.appUpdateCheck.collectAsStateCompat()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppLogoImage(48.dp)
            Column {
                Text("Streamlet", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Version ${cloudstream.desktop.app.update.BuildInfo.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer8()
    SectionCard {
        Group("Logs")
        Text("What the app did, including errors: attach streamlet.0.log when reporting a problem. Stream links are " +
            "logged without their query strings; account logins are never logged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(cloudstream.desktop.app.AppLog.dir.absolutePath, style = MaterialTheme.typography.labelMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer4()
        OutlinedButton(onClick = { cloudstream.desktop.app.openPath(cloudstream.desktop.app.AppLog.dir.apply { mkdirs() }) }) { Text("Open logs folder") }
    }
    Spacer8()
    SectionCard {
        Group("App updates")
        if (!up.configured) {
            Text(
                "Update checks are off in this build: no release repository is set (app.updateRepo in gradle.properties).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        SettingRow("Check for updates automatically", "At start-up and every 12 hours.") { Switch(auto, { gs.setAppUpdateCheck(it) }) }
        Spacer8()
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (val s = state) {
            cloudstream.desktop.app.update.UpdateState.NotConfigured, cloudstream.desktop.app.update.UpdateState.Idle ->
                Text("Not checked yet.", style = MaterialTheme.typography.bodySmall, color = muted)
            cloudstream.desktop.app.update.UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Checking...", style = MaterialTheme.typography.bodySmall)
            }
            is cloudstream.desktop.app.update.UpdateState.UpToDate -> Text("You have the latest version.", style = MaterialTheme.typography.bodySmall, color = muted)
            is cloudstream.desktop.app.update.UpdateState.Available -> ReleaseInfo(s.release) {
                if (s.release.assetUrl != null) Button(onClick = { up.download(s.release) }) { Text("Download and install") }
                OutlinedButton(onClick = { cloudstream.desktop.app.openInBrowser(s.release.pageUrl) }) { Text("Release page") }
            }
            is cloudstream.desktop.app.update.UpdateState.Downloading -> Column {
                Text("Downloading ${s.release.assetName ?: "update"}...", style = MaterialTheme.typography.bodySmall)
                Spacer4()
                s.fraction?.let { f -> androidx.compose.material3.LinearProgressIndicator({ f }, Modifier.fillMaxWidth()) }
                    ?: androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is cloudstream.desktop.app.update.UpdateState.Ready -> ReleaseInfo(s.release) {
                val cmd = remember(s.file) { cloudstream.desktop.app.update.linuxInstallCommand(s.file) }
                if (cmd == null) Button(onClick = {
                    // The installer replaces the app's files: close first (Windows MSI/EXE, AppImage).
                    if (runCatching { up.install(s.file) }.getOrDefault(false)) { c.services.close(); kotlin.system.exitProcess(0) }
                }) { Text("Install now (closes the app)") }
                else Column {
                    // Linux packages: installing needs root, so the app hands it to the software center or a terminal.
                    Text("Install it with your software center, or in a terminal:", style = MaterialTheme.typography.bodySmall)
                    Text(cmd, style = MaterialTheme.typography.labelMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runCatching { up.install(s.file) } }) { Text("Open in software center") }
                        OutlinedButton(onClick = {
                            runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(cmd), null) }
                            c.services.toast("Command copied")
                        }) { Text("Copy command") }
                    }
                    Text("Then restart Streamlet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is cloudstream.desktop.app.update.UpdateState.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer8()
        val busy = state is cloudstream.desktop.app.update.UpdateState.Checking || state is cloudstream.desktop.app.update.UpdateState.Downloading
        OutlinedButton(onClick = { up.check() }, enabled = !busy) { Text("Check now") }
    }
}

@Composable
private fun ReleaseInfo(rel: cloudstream.desktop.app.update.AppRelease, actions: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Version ${rel.version} is available", fontWeight = FontWeight.SemiBold, color = Color.White)
        if (rel.notes.isNotBlank()) Text(rel.notes.take(800), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun KeyRow(keys: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(keys, Modifier.width(220.dp), fontWeight = FontWeight.SemiBold)
        Text(what, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Group(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SettingRow(label: String, help: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (help != null) Text(help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}
