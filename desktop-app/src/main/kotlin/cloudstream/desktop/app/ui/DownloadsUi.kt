package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.DownloadItem
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.domain.guarded
import cloudstream.desktop.app.vm.DetailsViewModel
import java.io.File

private val Green = Color(0xFF46D369)

/** The icon at the right of an episode row: download, progress ring, paused, done, failed. */
@Composable
fun DownloadButton(item: DownloadItem?, onClick: () -> Unit) {
    val p = item?.progress
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick)
            .border(1.5.dp, Color.White.copy(if (item == null) 0.55f else 0f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (item?.status) {
            null -> Text("⤓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            DownloadItem.Status.Done -> Box(Modifier.size(40.dp).clip(CircleShape).background(Green.copy(0.18f)), contentAlignment = Alignment.Center) {
                Text("✓", color = Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            DownloadItem.Status.Failed -> Text("⚠", color = Color(0xFFFF8A80), fontSize = 20.sp)
            else -> {
                if (p != null) CircularProgressIndicator(progress = { p }, Modifier.size(36.dp), color = Accent, strokeWidth = 3.dp, trackColor = Color.White.copy(0.15f))
                else CircularProgressIndicator(Modifier.size(36.dp), color = Accent, strokeWidth = 3.dp)
                // Paused: two bars (the ⏸ glyph falls back to a coloured emoji on Windows).
                if (item.status == DownloadItem.Status.Paused) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { Box(Modifier.width(3.dp).height(11.dp).background(Color.White)) }
                } else Text(p?.let { "${(it * 100).toInt()}" } ?: "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "1.2 GB" / "340 MB". */
fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    bytes > 0 -> "${bytes shr 10} KB"
    else -> ""
}

/** What a download is doing, in one line. */
fun statusText(d: DownloadItem): String = when (d.status) {
    DownloadItem.Status.Queued -> "Waiting…"
    DownloadItem.Status.Downloading -> listOfNotNull(
        d.progress?.let { "${(it * 100).toInt()}%" },
        sizeText(d.bytes).takeIf { it.isNotEmpty() }?.let { s -> if (d.total > 0) "$s of ${sizeText(d.total)}" else s },
        if (d.segmentsTotal > 0) "segment ${d.segmentsDone}/${d.segmentsTotal}" else null,
    ).joinToString(" · ").ifEmpty { "Starting…" }
    DownloadItem.Status.Paused -> "Paused" + (d.progress?.let { " at ${(it * 100).toInt()}%" } ?: "")
    DownloadItem.Status.Done -> "Downloaded · ${sizeText(File(d.file).length())}" + (d.subtitles.orEmpty().size.takeIf { it > 0 }?.let { " · $it subtitle(s)" } ?: "")
    DownloadItem.Status.Failed -> "Failed: ${d.error ?: "unknown error"}"
}

/**
 * Choose what to download for [ep]: looks the sources up (best first, hidden ones left out, as in the player), lets
 * the user pick one and the subtitles to keep (the preferred language ticked).
 */
@Composable
fun DownloadDialog(c: AppController, vm: DetailsViewModel, d: MediaDetails, ep: EpisodeItem, onClose: () -> Unit) {
    val settings = c.services.settings
    val links = remember { mutableStateListOf<SourceLink>() }
    val subs = remember { mutableStateListOf<SubtitleItem>() }
    val picked = remember { mutableStateListOf<String>() } // subtitle urls
    var searching by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val pref = settings.subtitleLang.value
    LaunchedEffect(ep.data) {
        val p = c.providerByName(vm.card.provider)
        if (p == null) { message = "Provider is not loaded"; searching = false; return@LaunchedEffect }
        val out = guarded(p.linksTimeoutMs) {
            p.loadLinks(ep.data, { l -> synchronized(links) { if (links.none { it.url == l.url }) links += l } }, { s ->
                synchronized(subs) {
                    if (subs.none { it.url == s.url }) {
                        subs += s
                        // Preferred language ticked by default (one per language name).
                        if (pref.isNotBlank() && s.lang.contains(pref, true) && subs.none { o -> o.url in picked && o.lang.equals(s.lang, true) }) picked += s.url
                    }
                }
            })
        }
        searching = false
        message = when (out) {
            is Outcome.Ok -> if (links.isEmpty()) "No sources found" else null
            is Outcome.Failed -> "Source lookup failed: ${out.message}"
            is Outcome.TimedOut -> if (links.isEmpty()) "Timed out" else null
        }
    }
    fun download(l: SourceLink) {
        c.services.downloads.start(vm.card, d.name, d.isMovie, ep, l, subs.filter { it.url in picked }, maxQuality = settings.defaultQuality.value)
        c.services.toast("Downloading ${if (d.isMovie) d.name else ep.label} (${l.source.ifBlank { l.name }}, ${l.qualityLabel})")
        onClose()
    }
    fun downloadable(l: SourceLink) = l.playable && !l.isDash && !l.isLocalFile

    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).heightIn(max = 640.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF18181D)).clickable(enabled = false) {}.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(if (d.isMovie) d.name else "${d.name} · ${ep.label}", color = Color(0xFFB0B0B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (searching) CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                TextButton(onClick = onClose) { Text("Close") }
            }
            val ordered = settings.order(links.toList())
            // Best = the preferred quality (Settings → Player): the highest at or below it; sources above it only when
            // nothing fits. Equal qualities keep the priority order. "Auto" streams are capped when downloading.
            val prefQ = settings.defaultQuality.value
            val best = cloudstream.desktop.app.pickBest(ordered.filter { downloadable(it) }, prefQ) { it.quality }
            if (best != null) {
                val above = prefQ > 0 && best.quality > prefQ && best.quality != 400
                PrimaryButton("Download best: ${best.source.ifBlank { best.name }} · ${if (best.quality == 0 && prefQ > 0) "up to ${prefQ}p" else best.qualityLabel}", { download(best) }, leading = "⤓")
                Text(
                    if (above) "No source at ${prefQ}p or lower: this is the closest above it."
                    else if (prefQ > 0) "Picked for your preferred quality, ${prefQ}p (Settings → Player). Nothing above it is downloaded."
                    else "Preferred quality is \"Best available\" (Settings → Player).",
                    color = if (above) Color(0xFFE0A0A0) else Color(0xFF8A8A94), fontSize = 12.sp,
                )
            }
            message?.let { Text(it, color = Color(0xFFFF8A80)) }
            if (subs.isNotEmpty()) {
                Text("Subtitles to save with it", color = Color.White, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.heightIn(max = 130.dp)) {
                    items(subs.sortedWith(compareBy({ !it.lang.contains(pref, true) }, { it.lang.lowercase() })), key = { it.url }) { s ->
                        Row(Modifier.fillMaxWidth().clickable { if (s.url in picked) picked -= s.url else picked += s.url }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(s.url in picked, { on -> if (on) picked += s.url else picked -= s.url },
                                colors = CheckboxDefaults.colors(checkedColor = Accent))
                            Text(s.lang, color = Color.White)
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(0.1f))
            }
            Text(if (searching) "Sources (still searching…)" else "Sources", color = Color.White, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(ordered, key = { it.url }) { l ->
                    val ok = downloadable(l)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(l.qualityLabel, Modifier.width(52.dp), color = Color.White, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(l.name.ifBlank { l.source }, color = if (ok) Color.White else Color(0xFF77777F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(l.source.takeIf { it.isNotBlank() && it != l.name }, when { l.isDash -> "DASH (can't be downloaded)"; l.isM3u8 -> "HLS → .ts"; !l.playable -> "not a web link"; else -> "file" }).joinToString(" · "),
                                color = Color(0xFF8A8A94), fontSize = 12.sp,
                            )
                        }
                        OutlinedButton(onClick = { download(l) }, enabled = ok) { Text("Download") }
                    }
                }
            }
        }
    }
}

/** The Downloads section: every download grouped by title, with play / pause / resume / retry / delete. */
@Composable
fun DownloadsScreen(c: AppController) {
    val all by c.services.downloads.items.collectAsStateCompat()
    val folder = c.services.globalSettings.downloadFolder()
    Column(Modifier.fillMaxSize().screenPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.weight(1f))
            val running = all.count { it.status == DownloadItem.Status.Downloading }
            if (running > 0) Text("$running downloading  ·  ", color = Color(0xFFB0B0B8))
            Text(sizeText(all.filter { it.status == DownloadItem.Status.Done }.sumOf { File(it.file).length() }).ifEmpty { "Nothing yet" }, color = Color(0xFFB0B0B8))
            TextButton(onClick = { folder.mkdirs(); cloudstream.desktop.app.openPath(folder) }) { Text("Open folder") }
        }
        if (all.isEmpty()) {
            Text("Episodes you download show up here. Use the ⤓ button next to an episode on its title page.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        // One compact card per show (the arrow lists its episodes); a film is its own row.
        val expanded = remember { mutableStateListOf<String>() }
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            all.groupBy { it.provider to it.cardUrl }.values.sortedByDescending { g -> g.maxOf { it.addedAt } }.forEach { group ->
                val head = group.first()
                val key = "${head.provider}:${head.cardUrl}"
                if (group.size == 1 && head.isMovie) {
                    item(key = "m:$key") { DownloadRow(c, head, showTitle = true) }
                    return@forEach
                }
                val open = key in expanded
                item(key = "h:$key") { ShowCard(c, group, open) { if (open) expanded -= key else expanded += key } }
                if (open) items(group.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.addedAt })), key = { it.id }) { d ->
                    Box(Modifier.padding(start = 28.dp)) { DownloadRow(c, d) }
                }
            }
        }
    }
}

/** A show's downloads in one line: poster, title, how many are done / running / paused / failed, size, expand arrow. */
@Composable
private fun ShowCard(c: AppController, group: List<DownloadItem>, open: Boolean, onToggle: () -> Unit) {
    val head = group.first()
    val done = group.count { it.status == DownloadItem.Status.Done }
    val running = group.filter { it.active }
    val paused = group.count { it.status == DownloadItem.Status.Paused }
    val failed = group.count { it.status == DownloadItem.Status.Failed }
    val size = group.filter { it.status == DownloadItem.Status.Done }.sumOf { File(it.file).length() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF16161B)).clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteImage(head.posterUrl, Modifier.width(46.dp).height(68.dp).clip(RoundedCornerShape(4.dp)), placeholderText = head.titleName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(head.titleName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "$done of ${group.size} episode${if (group.size == 1) "" else "s"} downloaded",
                    running.size.takeIf { it > 0 }?.let { "$it downloading" },
                    paused.takeIf { it > 0 }?.let { "$it paused" },
                    failed.takeIf { it > 0 }?.let { "$it failed" },
                    sizeText(size).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                color = if (failed > 0) Color(0xFFE0A0A0) else Color(0xFF9A9AA2), fontSize = 13.sp,
            )
            // Overall progress of what is still downloading.
            if (running.isNotEmpty()) {
                val p = running.mapNotNull { it.progress }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(0.6f).height(3.dp), color = Accent, trackColor = Color.White.copy(0.12f))
                else LinearProgressIndicator(Modifier.fillMaxWidth(0.6f).height(3.dp), color = Accent, trackColor = Color.White.copy(0.12f))
            }
        }
        TextButton(onClick = { c.openDetails(head.card()) }) { Text("Open title") }
        // The arrow: down when closed, up when the episode list is showing.
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(0.08f)).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Text("›", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.rotate(if (open) -90f else 90f).padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun DownloadRow(c: AppController, d: DownloadItem, showTitle: Boolean = false) {
    val dl = c.services.downloads
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF16161B)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DownloadButton(d) {
            when (d.status) {
                DownloadItem.Status.Done -> c.playDownload(d)
                DownloadItem.Status.Paused, DownloadItem.Status.Failed -> dl.resume(d.id)
                else -> dl.pause(d.id)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (d.isMovie || showTitle) d.titleName else d.episodeLabel, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${d.sourceName} · ${if (d.quality in 1..399 || d.quality > 400) "${d.quality}p" else "Auto"} · ${statusText(d)}",
                color = if (d.status == DownloadItem.Status.Failed) Color(0xFFFF8A80) else Color(0xFF9A9AA2), fontSize = 12.sp, maxLines = 2)
            if (d.active || d.status == DownloadItem.Status.Paused) {
                val p = d.progress
                if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Color.White.copy(0.12f))
                else if (d.status == DownloadItem.Status.Downloading) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Color.White.copy(0.12f))
            }
        }
        when (d.status) {
            DownloadItem.Status.Done -> TextButton(onClick = { c.playDownload(d) }) { Text("▶  Play") }
            DownloadItem.Status.Downloading, DownloadItem.Status.Queued -> TextButton(onClick = { dl.pause(d.id) }) { Text("Pause") }
            DownloadItem.Status.Paused -> TextButton(onClick = { dl.resume(d.id) }) { Text("Resume") }
            DownloadItem.Status.Failed -> TextButton(onClick = { dl.resume(d.id) }) { Text("Retry") }
        }
        if (d.status == DownloadItem.Status.Done) TextButton(onClick = { cloudstream.desktop.app.openPath(File(d.file).parentFile) }) { Text("Folder") }
        TextButton(onClick = { dl.delete(d.id, files = true) }) { Text("Delete", color = Color(0xFFFF8A80)) }
    }
}
