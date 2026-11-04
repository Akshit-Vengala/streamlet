package cloudstream.desktop.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.guarded
import cloudstream.desktop.app.vm.ProviderResult
import cloudstream.desktop.app.vm.toCard
import kotlinx.coroutines.delay

private val SidePad = 40.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(c: AppController, focus: FocusRequester) {
    val s by c.search.state.collectAsStateCompat()
    Column(Modifier.fillMaxSize().padding(top = TopBarHeight + 12.dp)) {
        Column(Modifier.padding(horizontal = SidePad)) {
            OutlinedTextField(
                s.query, c.search::setQuery,
                Modifier.fillMaxWidth().focusRequester(focus).onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { c.search.search(); true } else false
                },
                singleLine = true,
                placeholder = { Text("Titles, people, genres... then press Enter", color = Color(0xFF7A7A84)) },
                leadingIcon = { Text("⌕", fontSize = 22.sp, color = Color(0xFFB0B0B8)) },
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White.copy(alpha = 0.8f), unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedContainerColor = Color(0xFF16161B), unfocusedContainerColor = Color(0xFF16161B), cursorColor = Accent,
                ),
            )
            Spacer8()
        }
        val st = c.services.settings
        val rev by c.services.catalogRevision.collectAsStateCompat()
        val disabled by st.disabledProviders.collectAsStateCompat()
        val excluded by st.searchExcluded.collectAsStateCompat()
        val available = remember(rev, disabled) { c.providers().filter { st.isEnabled(it.name) }.map { it.name }.sortedWith(naturalOrder) }
        val included = available.filter { it !in excluded }
        // Which extensions to search (this profile only). Changing it re-runs the current search.
        var showFilter by remember { mutableStateOf(false) }
        var lastExcluded by remember { mutableStateOf(excluded) }
        LaunchedEffect(excluded) {
            if (excluded != lastExcluded) { lastExcluded = excluded; s.submitted?.let { c.search.search(it) } }
        }
        Row(Modifier.padding(horizontal = SidePad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    available.isEmpty() -> "No providers loaded. Install and load extensions in the Extensions tab."
                    included.size == available.size -> "Searching all ${available.size} extensions."
                    else -> "Searching ${included.size} of ${available.size} extensions."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (available.isNotEmpty()) Chip(if (showFilter) "Filter ▴" else "Filter ▾", showFilter || included.size < available.size) { showFilter = !showFilter }
        }
        if (showFilter && available.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = SidePad, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("All", included.size == available.size) { st.setSearchExcluded(emptySet()) }
                Chip("None", included.isEmpty()) { st.setSearchExcluded(available.toSet()) }
                available.forEach { n -> Chip((if (n in excluded) "" else "✓ ") + n, n !in excluded) { st.setSearchIncluded(n, n in excluded) } }
            }
        }
        if (s.submitted == null) return@Column
        if (s.noProviders) {
            Text("No providers are enabled. Install extensions or enable providers in Settings.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = SidePad))
            return@Column
        }
        val done = s.results.count { it.status != ProviderResult.Status.Loading }
        var only by remember(s.submitted) { mutableStateOf<String?>(null) }
        val answered = s.results.filter { it.items.isNotEmpty() }
        Row(Modifier.padding(horizontal = SidePad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${s.totalItems} results for \"${s.submitted}\"", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (done < s.results.size) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                Text("${s.results.size - done} provider(s) still searching", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (answered.size > 1) {
            LazyRow(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = SidePad), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Chip("All", only == null) { only = null } }
                items(answered.map { it.provider }, key = { it }) { n -> Chip(n, only == n) { only = if (only == n) null else n } }
            }
        }
        LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            // Providers with results first, then pending, then empty, then errors.
            val ordered = s.results.filter { only == null || it.provider == only }.sortedBy { r ->
                when {
                    r.status == ProviderResult.Status.Done && r.items.isNotEmpty() -> 0
                    r.status == ProviderResult.Status.Loading -> 1
                    r.status == ProviderResult.Status.Done -> 2
                    else -> 3
                }
            }
            items(ordered, key = { it.provider }) { r -> ProviderResultRow(r, c) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color.White, selectedLabelColor = Color.Black,
            containerColor = Color(0xFF1E1E24), labelColor = Color(0xFFD0D0D6),
        ),
        border = null,
    )
}

@Composable
private fun ProviderResultRow(r: ProviderResult, c: AppController) {
    Column {
        Row(Modifier.padding(horizontal = SidePad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.provider, style = MaterialTheme.typography.titleLarge, color = Color.White)
            when (r.status) {
                ProviderResult.Status.Loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                ProviderResult.Status.Done -> Text("${r.items.size} results", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProviderResult.Status.Timeout -> Badge("timed out", WarnAmber.copy(alpha = 0.25f), MaterialTheme.colorScheme.onSurface)
                ProviderResult.Status.Error -> Badge("error", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        r.message?.let { ErrorText(it, Modifier.padding(horizontal = SidePad)) }
        when {
            r.items.isNotEmpty() -> CardRow(r.items, menu = { libraryMenu(c, it) }) { c.openDetails(it) }
            r.status == ProviderResult.Status.Loading -> Box(Modifier.padding(horizontal = SidePad, vertical = 12.dp)) { SkeletonRow() }
            r.status == ProviderResult.Status.Done -> Text("No results", Modifier.padding(horizontal = SidePad, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal artwork row; vertical padding leaves room for the hover zoom (rows clip their content). */
@Composable
private fun CardRow(
    items: List<MediaCard>, horizontal: Boolean = false, loadingMore: Boolean = false,
    onNearEnd: (() -> Unit)? = null, menu: ((MediaCard) -> List<CardMenuItem>)? = null, onClick: (MediaCard) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = SidePad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(items, key = { _, it -> it.url }) { i, card ->
            if (onNearEnd != null && i >= items.size - 3) LaunchedEffect(items.size) { onNearEnd() }
            PosterCard(card, { onClick(card) }, width = 150.dp, horizontal = horizontal, menu = menu?.let { m -> { m(card) } })
        }
        if (loadingMore) item { SkeletonCard(150.dp, horizontal) }
    }
}

@Composable
fun HomeScreen(c: AppController) {
    val s by c.home.state.collectAsStateCompat()
    val rev by c.services.catalogRevision.collectAsStateCompat()
    val settingsDisabled by c.services.settings.disabledProviders.collectAsStateCompat()
    val providers = remember(rev, settingsDisabled) { c.providers().filter { it.name !in settingsDisabled } }
    val withHome = providers.filter { it.hasMainPage }
    // From the library view model: watching something or removing it from the row shows at once.
    val lib by c.library.state.collectAsStateCompat()
    LaunchedEffect(rev) { c.library.refresh() }
    val history = lib.history.take(15)

    val preferred by c.services.settings.homeProvider.collectAsStateCompat()
    LaunchedEffect(withHome.map { it.name }, preferred) {
        val want = withHome.firstOrNull { it.name == preferred }?.name
        if (want != null && s.provider != want) c.home.selectProvider(want)
        else if (s.provider == null || withHome.none { it.name == s.provider }) c.home.selectProvider(withHome.firstOrNull()?.name)
    }
    if (withHome.isEmpty()) {
        Column(Modifier.fillMaxSize().screenPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing to show yet", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer8()
            Text(
                if (providers.isEmpty()) "Open Extensions, add a repository and install some extensions."
                else "None of the loaded providers offers a home page. Use Search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val featured = s.sections.firstOrNull { it.items.isNotEmpty() }?.items.orEmpty().take(6)
    // Like CloudStream: the whole page is a shimmer skeleton until the first row arrives (or every row has failed).
    val stillLoading = featured.isEmpty() && s.sections.isNotEmpty() && s.sections.any { it.error == null && (it.loading || !it.loadedOnce) }
    val pickProvider: (String) -> Unit = { name -> c.services.settings.setHomeProvider(name); c.home.selectProvider(name) }
    // The provider switcher floats bottom-right above everything and stays put while the page scrolls.
    Box(Modifier.fillMaxSize()) {
        if (stillLoading || s.sections.isEmpty() && s.provider == null) {
            // Keep the first sections loading while the skeleton is shown (the LazyColumn below is not composed yet).
            LaunchedEffect(s.provider, s.sections.size) { s.sections.indices.take(3).forEach { c.home.ensureLoaded(it) } }
            HomeLoadingShimmer(SidePad)
        } else HomeContent(c, s, featured, history, pickProvider)
        ProviderSwitcher(withHome.map { it.name }, s.provider, Modifier.align(Alignment.BottomEnd).padding(end = SidePad, bottom = 24.dp), pickProvider)
    }
}

/** Floating "current provider" pill; opens a menu of the providers that have a home page. */
@Composable
private fun ProviderSwitcher(names: List<String>, current: String?, modifier: Modifier, onPick: (String) -> Unit) {
    if (names.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2202027)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .clickable { open = true }.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("⇄", color = Accent, fontWeight = FontWeight.Bold)
            Text(current ?: "Provider", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("▴", color = Color(0xFFB0B0B8))
        }
        if (open) CardMenu(true, androidx.compose.ui.geometry.Offset.Zero, names.map { n ->
            CardMenuItem(n, checked = n == current) { onPick(n) }
        }) { open = false }
    }
}

@Composable
private fun HomeContent(c: AppController, s: cloudstream.desktop.app.vm.HomeState, featured: List<MediaCard>, history: List<cloudstream.desktop.contracts.HistoryEntry>, pickProvider: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "hero") {
            Hero(c, featured, s.provider, pickProvider)
        }
        if (history.isNotEmpty()) item(key = "continue") {
            Column(Modifier.padding(top = 8.dp)) {
                Box(Modifier.padding(horizontal = SidePad)) { RowTitle("Continue Watching") }
                LazyRow(contentPadding = PaddingValues(horizontal = SidePad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history, key = { it.id }) { h ->
                        val ep = listOfNotNull(h.season?.let { "S$it" }, h.episode?.let { "E$it" }).joinToString(" ")
                        PosterCard(h.toCard(), { c.openDetails(h.toCard()) }, width = 150.dp, horizontal = true,
                            caption = listOf(ep, h.apiName).filter { it.isNotBlank() }.joinToString("  ·  "),
                            menu = { libraryMenu(c, h.toCard(), continueWatching = true) })
                    }
                }
            }
        }
        itemsIndexed(s.sections, key = { i, sec -> "${s.provider}/$i/${sec.section.data}" }) { index, sec ->
            LaunchedEffect(s.provider, index) { c.home.ensureLoaded(index) }
            Column(Modifier.padding(top = 8.dp)) {
                Box(Modifier.padding(horizontal = SidePad)) { RowTitle(sec.title.ifBlank { "Featured" }) }
                when {
                    sec.error != null -> ErrorText(sec.error, Modifier.padding(horizontal = SidePad))
                    sec.items.isEmpty() && (sec.loading || !sec.loadedOnce) -> Box(Modifier.padding(horizontal = SidePad, vertical = 14.dp)) { SkeletonRow(horizontal = sec.horizontal) }
                    sec.items.isEmpty() -> Text("Nothing here", Modifier.padding(horizontal = SidePad), style = MaterialTheme.typography.bodySmall)
                    else -> CardRow(sec.items, sec.horizontal, sec.loading, onNearEnd = { c.home.loadMore(index) }, menu = { libraryMenu(c, it) }) { c.openDetails(it) }
                }
            }
        }
    }
}

/**
 * Billboard: cycles through the first items of the first row. Uses the title's real backdrop and plot when the provider
 * returns them (one cached `load` per featured title), otherwise a blurred enlargement of the poster.
 */
@Composable
private fun Hero(c: AppController, featured: List<MediaCard>, provider: String?, onProvider: (String) -> Unit) {
    var index by remember(featured.map { it.url }) { mutableStateOf(0) }
    val details = remember(provider) { mutableStateMapOf<String, MediaDetails?>() }
    LaunchedEffect(featured.map { it.url }) {
        while (featured.size > 1) { delay(10_000); index = (index + 1) % featured.size }
    }
    val card = featured.getOrNull(index)
    LaunchedEffect(card?.url) {
        val k = card ?: return@LaunchedEffect
        if (details.containsKey(k.url)) return@LaunchedEffect
        val p = c.providerByName(k.provider) ?: return@LaunchedEffect
        details[k.url] = (guarded(minOf(p.loadTimeoutMs, 20_000)) { p.load(k.url) } as? Outcome.Ok)?.value
    }
    Box(Modifier.fillMaxWidth().height(540.dp)) {
        Crossfade(card, animationSpec = tween(700)) { cur ->
            Box(Modifier.fillMaxSize()) {
                if (cur == null) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1B22), Ink))))
                } else {
                    val d = details[cur.url]
                    val backdrop = d?.backdropUrl?.takeIf { it.isNotBlank() }
                    if (backdrop != null) RemoteImage(backdrop, Modifier.fillMaxSize(), placeholderText = "", maxPx = BACKDROP_PX)
                    else {
                        RemoteImage(cur.posterUrl, Modifier.fillMaxSize().blur(40.dp), placeholderText = "", maxPx = BLUR_PX)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                        // Sharp poster on the right when there is no landscape art.
                        RemoteImage(cur.posterUrl, Modifier.align(Alignment.CenterEnd).padding(end = 80.dp, top = 40.dp).width(270.dp).height(405.dp)
                            .shadow(30.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)), placeholderText = cur.name, maxPx = 600)
                    }
                    // Netflix-style scrims: left for legibility, bottom to melt into the page.
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.7f).background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.95f), Ink.copy(alpha = 0.6f), Color.Transparent))))
                    Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Ink))))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = SidePad, bottom = 56.dp).width(620.dp)) {
                        Text(cur.provider.uppercase(), color = Accent, fontWeight = FontWeight.Black, letterSpacing = 3.sp, style = MaterialTheme.typography.labelLarge)
                        Spacer8()
                        Text(d?.name ?: cur.name, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer8()
                        val meta = listOfNotNull(
                            d?.score?.let { "${(it * 10).toInt()}% rating" }, d?.year?.toString(), (d?.type ?: cur.type), cur.quality,
                            d?.durationMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                        )
                        if (meta.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            meta.forEachIndexed { i, m -> Text(m, color = if (i == 0 && d?.score != null) Color(0xFF46D369) else Color(0xFFDADADF), fontWeight = FontWeight.SemiBold) }
                        }
                        d?.plot?.takeIf { it.isNotBlank() }?.let {
                            Spacer8()
                            Text(it, color = Color(0xFFE6E6EA), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer16()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton("Play", { c.openAndPlay(cur) })
                            GhostButton("ⓘ  More Info", { c.openDetails(cur) })
                        }
                    }
                }
            }
        }
        // Pager dots (the provider switcher floats bottom-right over the whole page, see HomeScreen).
        if (featured.size > 1) Row(Modifier.align(Alignment.BottomEnd).padding(end = SidePad, bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            featured.indices.forEach { i ->
                Box(Modifier.width(if (i == index) 22.dp else 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f)))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text("$label  $count", color = if (selected) Color.Black else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LibraryScreen(c: AppController) {
    val s by c.library.state.collectAsStateCompat()
    LaunchedEffect(Unit) { c.library.refresh() }
    Column(Modifier.fillMaxSize().padding(top = TopBarHeight + 12.dp)) {
        Box(Modifier.padding(horizontal = SidePad)) { ScreenTitle("My List") }
        if (s.history.isNotEmpty()) {
            Box(Modifier.padding(horizontal = SidePad)) { RowTitle("Continue Watching") }
            LazyRow(contentPadding = PaddingValues(horizontal = SidePad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.history, key = { it.id }) { h ->
                    val ep = listOfNotNull(h.season?.let { "S$it" }, h.episode?.let { "E$it" }).joinToString(" ")
                    PosterCard(h.toCard(), { c.openDetails(h.toCard()) }, width = 150.dp, horizontal = true, caption = ep.ifBlank { h.apiName },
                        menu = { libraryMenu(c, h.toCard(), continueWatching = true) })
                }
            }
        }
        // CloudStream's library tabs: one per watch status (plus All here).
        var filter by remember { mutableStateOf<cloudstream.desktop.contracts.WatchStatus?>(null) }
        val shown = s.bookmarks.filter { filter == null || it.status == filter }
        Row(Modifier.padding(horizontal = SidePad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("All", s.bookmarks.size, filter == null) { filter = null }
            cloudstream.desktop.contracts.WatchStatus.entries.forEach { st ->
                StatusChip(st.label, s.bookmarks.count { it.status == st }, filter == st) { filter = st }
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (s.bookmarks.isEmpty()) "Nothing saved yet. Right-click any title (or use the button on its page) to add it."
                else "Nothing in ${filter?.label ?: "the library"}.",
                Modifier.padding(horizontal = SidePad, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(160.dp), contentPadding = PaddingValues(horizontal = SidePad, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(shown, key = { it.id }) { b ->
                    PosterCard(b.toCard(), { c.openDetails(b.toCard()) }, width = 160.dp,
                        caption = if (filter == null) b.status.label else null, menu = { libraryMenu(c, b.toCard()) })
                }
            }
        }
    }
}
