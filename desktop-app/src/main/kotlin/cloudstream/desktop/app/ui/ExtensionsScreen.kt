package cloudstream.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.BrowserControl
import cloudstream.desktop.app.vm.ExtensionsState
import cloudstream.desktop.app.vm.ExtensionsViewModel
import cloudstream.desktop.app.vm.InstalledRow
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val OkGreen = Color(0xFF4CAF50)
val WarnAmber = Color(0xFFFFB300)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionsScreen(c: AppController) {
    val vm = c.extensions
    val s by vm.state.collectAsStateCompat()
    var repoInput by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Extensions") }

        item {
            SectionCard {
                Text("Add repository", fontWeight = FontWeight.SemiBold)
                Text(
                    "Paste a repo.json URL, a cs.repo / cloudstreamrepo:// link, or a shortcode.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer8()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        repoInput, { repoInput = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("https://.../repo.json  or  shortcode") },
                    )
                    Button(onClick = { vm.addRepo(repoInput); repoInput = "" }, enabled = repoInput.isNotBlank() && !s.addingRepo) {
                        if (s.addingRepo) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 0.dp), strokeWidth = 2.dp) else Text("Add")
                    }
                }
            }
        }

        if (s.message != null || s.error != null) {
            item {
                SectionCard {
                    s.message?.let { Text(it, color = OkGreen) }
                    s.error?.let { ErrorText(it) }
                    TextButton(onClick = vm::dismissMessage) { Text("Dismiss") }
                }
            }
        }

        item { BrowserCard(c.services.browser) }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Installed (${s.installed.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val gs = c.services.globalSettings
                    val mode by gs.extUpdates.collectAsStateCompat()
                    Dropdown(mode.label, cloudstream.desktop.app.ExtUpdateMode.entries.toList(), { it.label }) { gs.setExtUpdates(it) }
                    OutlinedButton(onClick = {
                        c.services.scope.launch { vm.applyUpdates(install = true).ifEmpty { listOf("All extensions are up to date") }.forEach { c.services.toast(it) } }
                    }) { Text("Update now") }
                }
            }
            if (s.startupLoading) { Spacer8(); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading installed extensions...", style = MaterialTheme.typography.bodySmall) }
        }
        if (s.installed.isEmpty() && !s.startupLoading) {
            item { Text("No extensions installed yet. Add a repository and install some below.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(s.installed, key = { "i:" + it.info.internalName }) { row -> InstalledRowView(row, s.busy[row.info.internalName], vm) }

        item { Spacer8(); Text("Available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { FilterBar(s, vm) }
        if (s.repos.isEmpty()) item { Text("No repositories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for (r in s.repos) {
            item(key = "r:" + r.repo.url) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(r.repo.name, fontWeight = FontWeight.SemiBold)
                        Text(r.repo.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { vm.refreshRepo(r.repo.url) }) { Text("Refresh") }
                    TextButton(onClick = { vm.removeRepo(r.repo.url) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                r.error?.let { ErrorText(it) }
            }
            val visible = s.visible(r)
            items(visible, key = { "a:" + it.repoUrl + it.internalName }) { p ->
                AvailableRow(p, s.installedNames.contains(p.internalName), s.busy[p.internalName], vm)
            }
            if (!r.loading && r.error == null && visible.isEmpty()) item { Text("No plugins match the filters.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(s: ExtensionsState, vm: ExtensionsViewModel) {
    Column {
        OutlinedTextField(s.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Filter by name") })
        Spacer4()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Language", style = MaterialTheme.typography.labelMedium)
            Dropdown(s.languageFilter ?: "All", listOf<String?>(null) + s.languages, { it ?: "All" }) { vm.setLanguageFilter(it) }
        }
        Spacer4()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.typeFilter == null, onClick = { vm.setTypeFilter(null) }, label = { Text("All types") })
            s.types.forEach { t -> FilterChip(selected = s.typeFilter == t, onClick = { vm.setTypeFilter(if (s.typeFilter == t) null else t) }, label = { Text(t) }) }
        }
    }
}

@Composable
fun <T> Dropdown(current: String, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(current) }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun AvailableRow(p: PluginInfo, installed: Boolean, busy: String?, vm: ExtensionsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontWeight = FontWeight.Medium)
                    Text("v${p.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.language?.let { Badge(it) }
                    if (p.status == 0) Badge("down", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    if (p.status == 2) Badge("slow", WarnAmber.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurface)
                    if (p.status == 3) Badge("beta only", WarnAmber.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurface)
                }
                if (p.tvTypes.isNotEmpty()) Text(p.tvTypes.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                busy != null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $busy", style = MaterialTheme.typography.bodySmall) }
                installed -> Text("Installed", color = OkGreen)
                else -> Button(onClick = { vm.install(p) }) { Text("Install") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun InstalledRowView(row: InstalledRow, busy: String?, vm: ExtensionsViewModel) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.info.name, fontWeight = FontWeight.SemiBold)
                    Text("v${row.info.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    row.info.language?.let { Badge(it) }
                    if (row.hasSettingsUi) Badge("Settings unavailable on desktop", WarnAmber.copy(alpha = 0.25f), MaterialTheme.colorScheme.onSurface)
                    row.updateTo?.let { Badge("update: v${it.version}", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
                }
                when (val l = row.load) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(MaterialTheme.colorScheme.outline); Text("  Not loaded", style = MaterialTheme.typography.bodySmall) }
                    is LoadResult.Ok -> LoadedSummary(l.apis)
                    is LoadResult.Failed -> if (l.blocked.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(MaterialTheme.colorScheme.error); Text("  Not loaded: blocked by the safety check (below)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { Dot(MaterialTheme.colorScheme.error); Text("  Failed to load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        ErrorText(l.error.take(300))
                        if (l.missingClasses.isNotEmpty()) {
                            Text("Missing classes (${l.missingClasses.size}):", style = MaterialTheme.typography.labelSmall)
                            Text(l.missingClasses.take(8).joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (l.missingClasses.size > 8) Text("... and ${l.missingClasses.size - 8} more", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (busy != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $busy", style = MaterialTheme.typography.bodySmall)
            } else {
                row.updateTo?.let { TextButton(onClick = { vm.update(row.info.internalName) }) { Text("Update") } }
                TextButton(onClick = { vm.reload(row.info.internalName) }) { Text("Reload") }
                TextButton(onClick = { vm.uninstall(row.info.internalName) }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            }
        }
        row.safety?.let { SafetyView(row, it, busy == null, vm) }
    }
}

/**
 * "Loaded: Provider, Provider-Anime · 140 extractors": the full provider / extractor lists only when opened.
 */
@Composable
private fun LoadedSummary(apis: List<String>) {
    var open by remember { mutableStateOf(false) }
    val providers = apis.filterNot { it.startsWith("extractor:") }
    val extractors = apis.filter { it.startsWith("extractor:") }.map { it.removePrefix("extractor:") }.distinct().sortedWith(naturalOrder)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).clickable { open = !open }.padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(OkGreen)
            Text(
                "  Loaded: " + (providers.joinToString(", ").ifEmpty { "extractors only" }) +
                    (if (extractors.isNotEmpty()) "  ·  ${extractors.size} extractor${if (extractors.size == 1) "" else "s"}" else ""),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (extractors.isNotEmpty() || providers.size > 3) Text(if (open) "   Hide details" else "   Show details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (open) Column(Modifier.padding(start = 14.dp, top = 4.dp)) {
            if (providers.isNotEmpty()) {
                Text("Providers (${providers.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(providers.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (extractors.isNotEmpty()) {
                Spacer4()
                Text("Extractors (${extractors.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(extractors.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/**
 * The safety check's findings: blocked abilities (with "Allow..." behind a warning), a blocked update, abilities
 * allowed earlier (revocable) and harmless notes.
 */
@Composable
private fun SafetyView(row: InstalledRow, sf: cloudstream.desktop.app.domain.ExtensionSafety, enabled: Boolean, vm: ExtensionsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val blocked = (sf.blocked + sf.pendingUpdate).distinctBy { it.id }
    val allowed = sf.items.filter { it.dangerous && it.allowed }
    val notes = sf.items.filter { !it.dangerous }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer4()
    when {
        blocked.isNotEmpty() -> {
            val what = if (sf.pendingUpdate.isNotEmpty() && sf.blocked.isEmpty()) "Update blocked for safety: the new version can" else "Blocked for safety: this extension can"
            Text("⚠  $what", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            blocked.forEach { Text("   •  ${it.label}   (${it.example})", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A streaming extension never needs this. Uninstall it unless you trust its author.", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirm = true }, enabled = enabled) {
                    Text(if (sf.pendingUpdate.isNotEmpty() && sf.blocked.isEmpty()) "Allow and update..." else "Allow and load...", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        allowed.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠  Allowed by you: " + allowed.joinToString(", ") { it.label }, style = MaterialTheme.typography.labelSmall, color = WarnAmber, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.revokeAndReload(row.info.internalName) }, enabled = enabled) { Text("Revoke") }
        }
        else -> Text("✓  Safety check: nothing risky found" + if (notes.isNotEmpty()) "  ·  also can " + notes.joinToString(", ") { it.label } else "",
            style = MaterialTheme.typography.labelSmall, color = OkGreen, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (notes.isNotEmpty() && (blocked.isNotEmpty() || allowed.isNotEmpty())) Text("Also can: " + notes.joinToString(", ") { it.label }, style = MaterialTheme.typography.labelSmall, color = muted)

    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Allow ${row.info.name}?") },
        text = {
            Column {
                Text("It will be able to:")
                blocked.forEach { Text("•  ${it.label}") }
                Spacer8()
                Text("Extensions run with your user's rights. If this one is malicious, it could harm your computer or steal data. " +
                    "Only allow it if you trust where it comes from.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { confirm = false; vm.allowAndLoad(row.info.internalName) }) { Text("Allow", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun BrowserCard(browser: BrowserControl) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(browser.isInstalled()) }
    var stage by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Browser component (${browser.label})", fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional. Needed by extensions that resolve links through a hidden browser or pass Cloudflare. " +
                        "First use downloads ~200 MB; you can do it now or it happens silently on first need.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                stage != null -> Text(stage!! + (fraction?.let { " ${(it * 100).toInt()}%" } ?: ""))
                installed -> Text("Installed", color = OkGreen)
                else -> Button(onClick = {
                    error = null; stage = "starting"
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { browser.install { st, f -> stage = st; fraction = f } }
                            installed = true
                        } catch (t: Throwable) { error = "${t::class.simpleName}: ${t.message}" }
                        stage = null
                    }
                }) { Text("Download now") }
            }
        }
        if (stage != null) { Spacer4(); fraction?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { ErrorText(it) }
    }
}
