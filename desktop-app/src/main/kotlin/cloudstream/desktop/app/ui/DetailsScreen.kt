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
                            val rp = c.services.library.resume(episodeId(vm.card.provider, ep.data))
                            val frac = rp?.takeIf { it.durationMs > 0 && it.positionMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs }
                            EpisodeRow(i + 1, ep, d.posterUrl, frac) { c.playEpisode(vm, ep) }
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
    }
}

/** Full-bleed artwork: the backdrop if there is one, else a blurred poster, with Netflix-style scrims. */
@Composable
private fun Backdrop(backdropUrl: String?, posterUrl: String?, height: androidx.compose.ui.unit.Dp? = null) {
    Box((if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize())) {
        if (!backdropUrl.isNullOrBlank()) RemoteImage(backdropUrl, Modifier.fillMaxSize(), placeholderText = "", maxPx = 1920)
        else {
            RemoteImage(posterUrl, Modifier.fillMaxSize().blur(44.dp), placeholderText = "")
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
                .shadow(30.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)), placeholderText = d.name,
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val target = vm.playTarget(s)
                val resume = target?.let { s.resumeMs[it.data] }?.takeIf { it > 0 }
                PrimaryButton(
                    when {
                        target == null -> "Play"
                        resume != null -> "Resume" + (if (!d.isMovie) " ${target.label.substringBefore(" - ")}" else "")
                        else -> if (d.isMovie) "Play" else "Play ${target.label.substringBefore(" - ")}"
                    },
                    { target?.let { c.playEpisode(vm, it) } }, enabled = target != null,
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
private fun EpisodeRow(n: Int, ep: EpisodeItem, fallbackThumb: String?, progress: Float?, onPlay: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0xFF1C1C22) else Color.Transparent)
            .hoverable(src).clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
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
                Text(ep.name?.takeIf { it.isNotBlank() } ?: ep.label, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                ep.group?.let { Badge(it) }
            }
            ep.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            // Watch progress is the bar on the thumbnail (no "Resume at" text).
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 48.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}


fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
