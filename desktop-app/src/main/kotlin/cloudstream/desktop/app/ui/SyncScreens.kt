package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.openInBrowser
import cloudstream.desktop.app.sync.LoginKind
import cloudstream.desktop.app.sync.PinChallenge
import cloudstream.desktop.app.sync.SubResult
import cloudstream.desktop.app.sync.SubtitleService
import cloudstream.desktop.app.sync.TrackEntry
import cloudstream.desktop.app.sync.TrackStatus
import cloudstream.desktop.app.sync.Tracker
import kotlinx.coroutines.launch
import java.io.File

// ------------------------------------------------------------------------------------------------ Settings: accounts

@Composable
fun AccountsSection(c: AppController) {
    val sync = c.services.sync
    val rev by sync.revision.collectAsStateCompat()
    var passwordFor by remember { mutableStateOf<Pair<String, (String, String) -> Unit>?>(null) }
    var pin by remember { mutableStateOf<Pair<Tracker, PinChallenge>?>(null) }
    fun run(what: String, block: suspend () -> Unit) = c.services.scope.launch {
        runCatching { block() }.onSuccess { c.services.toast("$what: done") }.onFailure { c.services.toast("$what failed: ${it.message}") }
        sync.changed()
    }

    SectionCard {
        Text("Accounts & sync", fontWeight = FontWeight.SemiBold)
        Text(
            "Tracking updates your list when you finish an episode. AniList, MyAnimeList and Simkl need your own free API client " +
                "(CloudStream's keys are not public); Kitsu and the subtitle sites use your username and password. Accounts are per profile " +
                "and stored in this profile's settings file.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer8()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Update tracking automatically when an episode finishes")
            Switch(remember(rev) { sync.autoUpdate }, { sync.autoUpdate = it })
        }
        Spacer8()
        Text("Tracking", fontWeight = FontWeight.Medium)
        sync.trackers.forEach { t ->
            key(t.id, rev) {
                var cid by remember { mutableStateOf(t.clientId.orEmpty()) }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
                        Text(t.loggedInAs?.let { "Signed in as $it" } ?: "Not signed in", Modifier.weight(1f),
                            color = if (t.loggedIn) Color(0xFF46D369) else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.loggedIn) TextButton(onClick = { t.logout(); sync.changed() }) { Text("Sign out") }
                        else {
                            TextButton(onClick = { openInBrowser(t.signupUrl) }) { Text("Create account") }
                            TextButton(onClick = {
                                if (t.needsClientId) t.clientId = cid
                                when (t.loginKind) {
                                    LoginKind.Browser -> run("${t.name} sign-in") { t.loginBrowser { openInBrowser(it) } }
                                    LoginKind.DevicePin -> c.services.scope.launch {
                                        runCatching { t.startPin() }.onSuccess { ch -> pin = t to ch; openInBrowser(ch.verificationUrl) }
                                            .onFailure { c.services.toast("${t.name}: ${it.message}") }
                                    }
                                    LoginKind.Password -> passwordFor = t.name to { u, p -> run("${t.name} sign-in") { t.loginPassword(u, p) } }
                                }
                            }, enabled = !t.needsClientId || cid.isNotBlank()) { Text("Sign in") }
                        }
                    }
                    if (t.needsClientId && !t.loggedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(cid, { cid = it; t.clientId = it }, singleLine = true, placeholder = { Text("Client ID") }, modifier = Modifier.width(360.dp))
                        }
                        SelectionContainer { Text(t.clientIdHelp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        Spacer8()
        Text("Online subtitles", fontWeight = FontWeight.Medium)
        sync.subtitles.forEach { s ->
            key(s.id, rev) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
                    Text(
                        when {
                            s.loggedInAs != null -> "Signed in as ${s.loggedInAs}"
                            s.loginKind == null -> "No account needed"
                            s.requiresLogin -> "Account required"
                            else -> "Search works without an account; downloads need one"
                        },
                        Modifier.weight(1f), color = if (s.loggedInAs != null) Color(0xFF46D369) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.loggedInAs != null) TextButton(onClick = { s.logout(); sync.changed() }) { Text("Sign out") }
                    else if (s.loginKind == LoginKind.Password) {
                        s.signupUrl?.let { u -> TextButton(onClick = { openInBrowser(u) }) { Text("Create account") } }
                        TextButton(onClick = { passwordFor = s.name to { u, p -> run("${s.name} sign-in") { s.loginPassword(u, p) } } }) { Text("Sign in") }
                    }
                }
            }
        }
    }

    passwordFor?.let { (svc, submit) ->
        var user by remember(svc) { mutableStateOf("") }
        var pass by remember(svc) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text("Sign in to $svc") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username or email") })
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = { TextButton(onClick = { submit(user.trim(), pass); passwordFor = null }, enabled = user.isNotBlank() && pass.isNotEmpty()) { Text("Sign in") } },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Cancel") } },
        )
    }
    pin?.let { (t, ch) ->
        LaunchedEffect(ch) {
            val ok = runCatching { t.pollPin(ch) }.getOrElse { c.services.toast("${t.name}: ${it.message}"); false }
            c.services.toast(if (ok) "${t.name}: signed in" else "${t.name}: PIN expired"); pin = null; sync.changed()
        }
        AlertDialog(
            onDismissRequest = { pin = null },
            title = { Text("Sign in to ${t.name}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter this code at ${ch.verificationUrl}")
                    SelectionContainer { Text(ch.userCode, style = MaterialTheme.typography.displaySmall, color = Color.White) }
                    CircularProgressIndicator(color = Accent)
                }
            },
            confirmButton = { TextButton(onClick = { openInBrowser(ch.verificationUrl) }) { Text("Open page") } },
            dismissButton = { TextButton(onClick = { pin = null }) { Text("Cancel") } },
        )
    }
}


// ------------------------------------------------------------------------------------------------ Details: tracking

@Composable
fun TrackingPanel(c: AppController, d: MediaDetails) {
    val sync = c.services.sync
    val rev by sync.revision.collectAsStateCompat()
    val active = remember(rev, d) { sync.active(d) }
    if (active.isEmpty()) return
    Column(Modifier.padding(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tracking", style = MaterialTheme.typography.titleLarge, color = Color.White)
        active.forEach { t -> key(t.id, rev) { TrackerRow(c, t, d) } }
    }
}

@Composable
private fun TrackerRow(c: AppController, t: Tracker, d: MediaDetails) {
    val sync = c.services.sync
    var mediaId by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<TrackEntry?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        loading = true; err = null
        runCatching {
            mediaId = sync.resolve(t, d)
            entry = mediaId?.let { t.entry(it) }
        }.onFailure { err = it.message }
        loading = false
    }
    fun push(status: TrackStatus? = null, progress: Int? = null, score: Int? = null) {
        val id = mediaId ?: return
        c.services.scope.launch {
            runCatching { t.update(id, status, progress, score, d.episodes.firstOrNull()?.season) }
                .onSuccess { reload++ }.onFailure { c.services.toast("${t.name}: ${it.message}") }
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF16161B), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.name, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.width(110.dp))
        val e = entry
        when {
            loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
            err != null -> ErrorText(err!!, Modifier.weight(1f))
            mediaId == null -> Text("No match found", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            e != null -> {
                Text(e.title ?: "#${e.mediaId}", Modifier.weight(1f).clickable { e.url?.let { openInBrowser(it) } }, color = Color(0xFFD0D0D6), maxLines = 1)
                Dropdown(e.status?.label ?: "Status", TrackStatus.entries.toList(), { it.label }) { push(status = it) }
                if (!d.isMovie) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { push(progress = (e.progress - 1).coerceAtLeast(0)) }) { Text("-") }
                    Text("${e.progress}${e.total?.let { " / $it" } ?: ""} ep", color = Color.White)
                    TextButton(onClick = { push(progress = e.progress + 1, status = if (e.status == TrackStatus.None || e.status == TrackStatus.Planning) TrackStatus.Watching else null) }) { Text("+") }
                }
                Dropdown(e.score?.let { "Score $it" } ?: "Score", (0..10).toList(), { if (it == 0) "No score" else "$it" }) { push(score = it) }
            }
            else -> Text("Not available", Modifier.weight(1f))
        }
        TextButton(onClick = { editing = true }) { Text(if (mediaId == null) "Link" else "Change") }
    }
    if (editing) {
        var v by remember { mutableStateOf(mediaId.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Link to ${t.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Paste the ${t.name} ID or page URL of \"${d.name}\"." + if (t.id == "simkl") " Simkl ids look like show:12345, movie:123 or anime:456." else "",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = if (t.id == "simkl") v.trim() else Regex("(\\d+)").find(v.substringAfter("/anime/").ifBlank { v })?.value
                    sync.setMatch(t, d, id?.takeIf { it.isNotBlank() }); editing = false; reload++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { sync.setMatch(t, d, null); editing = false; reload++ }) { Text("Unlink") } },
        )
    }
}

// ------------------------------------------------------------------------------------------------ Player: online subtitles

@Composable
fun OnlineSubtitlesDialog(c: AppController, d: MediaDetails?, title: String, ep: EpisodeItem?, onLoaded: (File, String) -> Unit, onDismiss: () -> Unit) {
    val sync = c.services.sync
    var lang by remember { mutableStateOf(c.services.settings.subtitleLang.value.ifBlank { "English" }) }
    var query by remember { mutableStateOf(d?.name ?: title) }
    val results = remember { mutableStateListOf<SubResult>() }
    val status = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var searching by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searching) {
        if (searching == 0) return@LaunchedEffect
        results.clear(); status.clear()
        val q = sync.subtitleQuery(d, query, ep, lang).copy(title = query)
        sync.subtitles.forEach { s ->
            launch {
                status[s.id] = "searching..."
                runCatching { s.search(q) }
                    .onSuccess { r -> results += r; status[s.id] = "${r.size} result(s)" }
                    .onFailure { status[s.id] = "error: ${it.message}" }
            }
        }
    }
    LaunchedEffect(Unit) { searching = 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online subtitles") },
        text = {
            Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(lang, { lang = it }, singleLine = true, label = { Text("Language") }, modifier = Modifier.width(140.dp))
                    OutlinedButton(onClick = { searching++ }) { Text("Search") }
                }
                val ids = d?.syncIds.orEmpty().filterKeys { it == "imdb" || it == "tmdb" }
                Text(
                    listOfNotNull(ep?.let { listOfNotNull(it.season?.let { s -> "S$s" }, it.episode?.let { e -> "E$e" }).joinToString("") }?.takeIf { it.isNotBlank() },
                        ids.entries.joinToString(" ") { "${it.key}=${it.value}" }.takeIf { it.isNotBlank() }).joinToString("  -  ").ifBlank { "Searching by title" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sync.subtitles.forEach { s -> status[s.id]?.let { Text("${s.name}: $it", style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
                LazyColumn(Modifier.height(320.dp)) {
                    items(results, key = { it.serviceId + it.data }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                                busy = r.data
                                c.services.scope.launch {
                                    val svc: SubtitleService = sync.subtitles.first { it.id == r.serviceId }
                                    runCatching { svc.download(r, File(c.services.rootDir, "subtitles")) }
                                        .onSuccess { f -> onLoaded(f, r.lang); c.services.toast("Loaded subtitle: ${f.name}") }
                                        .onFailure { c.services.toast("${r.service}: ${it.message}") }
                                    busy = null
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Badge(r.service)
                            Text(r.name, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            if (r.hearingImpaired) Badge("HI")
                            Badge(r.lang)
                            if (busy == r.data) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
