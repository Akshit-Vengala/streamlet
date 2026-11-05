package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.app.vm.PlayRequest
import cloudstream.desktop.app.vm.episodeId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(c: AppController, vm: DetailsViewModel) {
    val s by vm.state.collectAsStateCompat()
    // Back from the player (or another screen): progress may have changed.
    androidx.compose.runtime.LaunchedEffect(vm) { vm.refreshProgress() }
    val downloads by c.services.downloads.items.collectAsStateCompat()
    var downloadFor by remember { mutableStateOf<EpisodeItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        when (val l = s.load) {
            DetailsLoad.Loading -> Box(Modifier.fillMaxSize()) {
                Backdrop(null, vm.card.posterUrl)
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 80.dp)) {
                    Text(vm.card.name, style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Spacer8()
                    LinearProgressIndicator(Modifier.width(260.dp), color = Accent, trackColor = Color.White.copy(alpha = 0.15f))
                }
            }
            is DetailsLoad.Failed -> Column(Modifier.fillMaxSize().screenPadding()) {
                Text(vm.card.name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer8()
                ErrorText("Could not load details: ${l.message}")
                Spacer8()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { PrimaryButton("Retry", { vm.load() }, leading = null); GhostButton("Back", { c.back() }) }
            }
            is DetailsLoad.Loaded -> {
                val d = l.details
                val episodes = vm.visibleEpisodes(s)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
                    item { Header(c, d, vm, s) }
                    item { TrackingPanel(c, d) }
                    val seasons = d.episodes.mapNotNull { it.season }.distinct().sorted()
                    val groups = d.episodes.mapNotNull { it.group }.distinct()
                    if (!d.isMovie || episodes.size > 1) {
                        item {
                            Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Episodes", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
                                Text("${episodes.size} episode(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (seasons.size > 1 || groups.size > 1) item {
                            FlowRow(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (seasons.size > 1) seasons.forEach { n -> PillChip("Season $n", s.season == n) { vm.selectSeason(n) } }
                                if (groups.size > 1) groups.forEach { g -> PillChip(g, s.group == g) { vm.selectGroup(g) } }
                            }
                        }
                        itemsIndexed(episodes, key = { _, it -> it.data }) { i, ep ->
                            val rp = s.progress[ep.data]
                            val watched = rp?.watched == true
                            val frac = if (watched) 1f else rp?.takeIf { it.durationMs > 0 && it.positionMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs }
                            val dlId = episodeId(vm.card.provider, ep.data)
                            val dl = downloads.firstOrNull { it.id == dlId }
                            EpisodeRow(i + 1, ep, d.posterUrl, frac, watched, dl, onPlay = { c.playEpisode(vm, ep) }, onDownload = {
                                when (dl?.status) {
                                    null, cloudstream.desktop.app.DownloadItem.Status.Failed -> downloadFor = ep
                                    cloudstream.desktop.app.DownloadItem.Status.Paused -> c.services.downloads.resume(dl.id)
                                    cloudstream.desktop.app.DownloadItem.Status.Done -> c.playEpisode(vm, ep)
                                    else -> c.services.downloads.pause(dl.id)
                                }
                            }) {
                                buildList {
                                    add(CardMenuItem("Play episode") { c.playEpisode(vm, ep) })
                                    if (dl == null || dl.status == cloudstream.desktop.app.DownloadItem.Status.Failed) add(CardMenuItem("Download…") { downloadFor = ep })
                                    else {
                                        add(CardMenuItem("Download from another source…") { downloadFor = ep })
                                        add(CardMenuItem("Delete download", destructive = true) { c.services.downloads.delete(dl.id, files = true) })
                                    }
                                    add(CardMenuItem("Reload links and play") { c.playEpisode(vm, ep, online = true) })
                                    if (watched) add(CardMenuItem("Mark as unwatched", divider = true) { vm.setWatched(listOf(ep), false) })
                                    else add(CardMenuItem("Mark as watched", divider = true) { vm.setWatched(listOf(ep), true) })
                                    if (i > 0) add(CardMenuItem("Mark all above as watched") { vm.setWatched(episodes.take(i), true) })
                                    add(CardMenuItem("Mark this and all above as watched") { vm.setWatched(episodes.take(i + 1), true) })
                                    if (episodes.take(i + 1).any { s.progress[it.data] != null })
                                        add(CardMenuItem("Clear progress of this and all above", destructive = true) { vm.setWatched(episodes.take(i + 1), false) })
                                }
                            }
                        }
                    }
                    if (d.recommendations.isNotEmpty()) {
                        item { Box(Modifier.padding(start = 48.dp, top = 28.dp)) { RowTitle("More Like This") } }
                        item {
                            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(d.recommendations, key = { it.url }) { r -> PosterCard(r, { c.openDetails(r) }, width = 140.dp, menu = { libraryMenu(c, r) }) }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { c.back() }, modifier = Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = TopBarHeight)) {
            Text("←  Back", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        val loaded = (s.load as? DetailsLoad.Loaded)?.details
        downloadFor?.let { ep -> if (loaded != null) DownloadDialog(c, vm, loaded, ep) { downloadFor = null } }
    }
}

/** Full-bleed artwork: the backdrop if there is one, else a blurred poster, with Netflix-style scrims. */
@Composable
private fun Backdrop(backdropUrl: String?, posterUrl: String?, height: androidx.compose.ui.unit.Dp? = null) {
    Box((if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize())) {
        if (!backdropUrl.isNullOrBlank()) RemoteImage(backdropUrl, Modifier.fillMaxSize(), placeholderText = "", maxPx = BACKDROP_PX)
        else {
            RemoteImage(posterUrl, Modifier.fillMaxSize().blur(44.dp), placeholderText = "", maxPx = BLUR_PX)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.96f), Ink.copy(alpha = 0.55f), Color.Transparent), endX = 1400f)))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Ink))))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(c: AppController, d: MediaDetails, vm: DetailsViewModel, s: cloudstream.desktop.app.vm.DetailsState) {
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        Backdrop(d.backdropUrl, d.posterUrl ?: vm.card.posterUrl, 560.dp)
        if (d.backdropUrl.isNullOrBlank()) RemoteImage(
            d.posterUrl ?: vm.card.posterUrl, Modifier.align(Alignment.CenterEnd).padding(end = 80.dp, top = 50.dp).width(260.dp).height(390.dp)
                .shadow(30.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)), placeholderText = d.name, maxPx = 600,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 36.dp).width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(vm.card.provider.uppercase(), color = Accent, fontWeight = FontWeight.Black, letterSpacing = 3.sp, style = MaterialTheme.typography.labelLarge)
            Text(d.name, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                d.score?.let { Text("${(it * 10).toInt()}% rating", color = Color(0xFF46D369), fontWeight = FontWeight.Bold) }
                d.year?.let { Text(it.toString(), color = Color(0xFFDADADF)) }
                Text(d.type, Modifier.border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp), color = Color(0xFFDADADF), style = MaterialTheme.typography.labelMedium)
                d.durationMin?.let { Text(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m", color = Color(0xFFDADADF)) }
                if (!d.isMovie) Text("${d.episodes.size} episodes", color = Color(0xFFDADADF))
            }
            d.plot?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFE6E6EA), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (d.tags.isNotEmpty()) Text(d.tags.take(6).joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer4()
            // The next episode's air time (the plugin's "next airing", else the first upcoming episode in the list).
            val nextAir = d.nextAiring?.takeIf { it.atMs > System.currentTimeMillis() }?.let { "E${it.episode}" to it.atMs }
                ?: d.episodes.firstOrNull { it.upcoming() }?.let { e -> (e.episode?.let { "E$it" } ?: "Next episode") to e.airDate!! }
            nextAir?.let { (label, at) -> AirCountdown(at, prefix = "$label airs") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val target = vm.playTarget(s)
                val resume = target?.let { s.resumeMs[it.data] }?.takeIf { it > 0 }
                val targetUpcoming = target?.upcoming() == true
                PrimaryButton(
                    when {
                        target == null -> "Play"
                        targetUpcoming -> "${target!!.episode?.let { "E$it" } ?: "Next episode"} is not out yet"
                        // Resume also when the previous episode was finished: "Resume E2" after leaving E1 in the credits.
                        resume != null || (!d.isMovie && s.progress.isNotEmpty() && !s.watched(target)) ->
                            "Resume" + (if (!d.isMovie) " ${target.label.substringBefore(" - ")}" else "")
                        else -> if (d.isMovie) "Play" else "Play ${target.label.substringBefore(" - ")}"
                    },
                    { target?.let { c.playEpisode(vm, it) } }, enabled = target != null && !targetUpcoming,
                )
                // CloudStream's bookmark button: pick a watch status (or remove).
                Box {
                    var open by remember { mutableStateOf(false) }
                    GhostButton(s.status?.let { "✓  ${it.label}  ▾" } ?: "+  My List  ▾", { open = true })
                    val below = with(androidx.compose.ui.platform.LocalDensity.current) { 46.dp.toPx() }
                    if (open) CardMenu(true, androidx.compose.ui.geometry.Offset(0f, below), buildList {
                        cloudstream.desktop.contracts.WatchStatus.entries.forEach { st ->
                            add(CardMenuItem(st.label, checked = st == s.status) { vm.setStatus(st); c.library.refresh() })
                        }
                        if (s.status != null) add(CardMenuItem("Remove from library", destructive = true, divider = true) { vm.setStatus(null); c.library.refresh() })
                    }) { open = false }
                }
            }
        }
    }
}

@Composable
private fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) Color.White else Color(0xFF1E1E24)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) Color.Black else Color(0xFFD0D0D6), fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EpisodeRow(
    n: Int, ep: EpisodeItem, fallbackThumb: String?, progress: Float?, watched: Boolean,
    download: cloudstream.desktop.app.DownloadItem?,
    onPlay: () -> Unit, onDownload: () -> Unit, menu: () -> List<CardMenuItem>,
) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var menuItems by remember { mutableStateOf(emptyList<CardMenuItem>()) }
    Box(Modifier.padding(horizontal = 40.dp, vertical = 2.dp)) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0xFF1C1C22) else Color.Transparent)
            .hoverable(src).onSecondaryPress { at -> menuItems = menu(); menuAt = at }
            .clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val upcoming = ep.upcoming()
        Text("${ep.episode ?: n}", Modifier.width(30.dp), style = MaterialTheme.typography.headlineSmall, color = Color(0xFF8A8A94))
        Box(Modifier.width(176.dp).height(99.dp).clip(RoundedCornerShape(6.dp))) {
            RemoteImage(ep.posterUrl ?: fallbackThumb, Modifier.fillMaxSize(), placeholderText = ep.label)
            if (hovered) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 30.sp)
            }
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).align(Alignment.BottomStart).background(Accent))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val title = withoutUpcoming(ep.name)?.takeIf { it.isNotBlank() } ?: ep.episode?.let { "Episode $it" } ?: ep.label
                Text(title, color = if (upcoming) Color(0xFFB0B0B8) else Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                ep.group?.let { Badge(it) }
                if (watched) Text("✓ Watched", color = Color(0xFF8A8A94), style = MaterialTheme.typography.labelMedium)
            }
            if (upcoming) AirCountdown(ep.airDate!!)
            else ep.airDate?.let { Text("Aired ${airDateText(it)}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF8A8A94)) }
            withoutUpcoming(ep.description)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            // Watch progress is the bar on the thumbnail (no "Resume at" text).
        }
        DownloadButton(download, onDownload)
    }
    menuAt?.let { at -> CardMenu(true, at, menuItems) { menuAt = null } }
    }
    HorizontalDivider(Modifier.padding(horizontal = 48.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}


fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Plugins mark episodes not out yet with "Upcoming" in the name or description; the countdown says it instead. */
private val upcomingWord = Regex("""\s*[\[(]?\b(upcoming|coming soon)\b[\])]?\s*[:\-–|·]?\s*""", RegexOption.IGNORE_CASE)
fun withoutUpcoming(s: String?): String? = s?.replace(upcomingWord, " ")?.trim()

private val airFormat = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm")
fun airDateText(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(airFormat)

/** "in 2d 4h 13m" (more than a day away) or a ticking "in 04:13:22", then the local date and time. */
fun countdownText(ms: Long, now: Long): String {
    val left = ((ms - now) / 1000).coerceAtLeast(0)
    val d = left / 86_400; val h = (left % 86_400) / 3600; val m = (left % 3600) / 60; val sec = left % 60
    return when {
        left == 0L -> "now"
        d > 0 -> "in ${d}d ${h}h ${m}m"
        else -> "in %02d:%02d:%02d".format(h, m, sec)
    }
}

@Composable
private fun AirCountdown(atMs: Long, prefix: String = "Airs") {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(atMs) {
        while (now < atMs) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⏱ $prefix ${countdownText(atMs, now)}", color = Color.Black, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(horizontal = 7.dp, vertical = 2.dp))
        Text(airDateText(atMs), color = Color(0xFFB0B0B8), style = MaterialTheme.typography.labelMedium)
    }
}
