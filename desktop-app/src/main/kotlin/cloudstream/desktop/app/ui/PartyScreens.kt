package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.party.ControlMode
import cloudstream.desktop.app.party.PartyInfo
import cloudstream.desktop.app.party.PartyManager

/** The watch party panel: start / join when not in one; invite code, members and chat when in one. */
@Composable
fun PartyDialog(c: AppController, onDismiss: () -> Unit) {
    val info by c.party.active.collectAsStateCompat()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch party") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val i = info
                if (i == null) NotInParty(c) else InParty(c, i)
            }
        },
        // "Hide" only closes this panel: the party keeps running (the top bar chip reopens it).
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (info != null) "Hide" else "Close") } },
        dismissButton = {
            val i = info
            if (i != null) {
                val ui by i.session.ui.collectAsStateCompat()
                Row {
                    if (ui.isHost) TextButton(onClick = { c.party.leave(endForAll = true); onDismiss() }) {
                        Text("End party", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { c.party.leave(); onDismiss() }) {
                        Text(if (ui.isHost && ui.members.size > 1) "Leave (pass host on)" else "Leave party", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}

/** The name others see in the party (remembered for next time). */
@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        name, { onChange(it.take(40)) }, singleLine = true, label = { Text("Your name") },
        isError = name.isBlank(), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotInParty(c: AppController) {
    Text(
        "Watch together in sync: play, pause, seek and episode changes are shared, and there is a chat. " +
            "Everyone streams from their own app (they need the same extension); nothing but an encrypted " +
            "\"what and where\" goes over the internet.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var name by remember { mutableStateOf(c.party.defaultName()) }
    NameField(name) { name = it }
    Button(onClick = { c.party.start(name) }, enabled = name.isNotBlank()) { Text("Start a watch party with this video") }
    Spacer(Modifier.height(4.dp))
    JoinField(c, name, onJoined = {})
}

@Composable
private fun JoinField(c: AppController, name: String, onJoined: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (name.isBlank()) { error = "Enter your name first"; return }
        if (c.party.join(code, name)) onJoined() else error = "That is not a watch party code (WP-XXXXX-XXXXX-XXXXX-XXXXX)"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            code, { code = it; error = null }, singleLine = true, label = { Text("Invite code") },
            isError = error != null, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }),
        )
        OutlinedButton(onClick = { go() }, enabled = code.isNotBlank() && name.isNotBlank()) { Text("Join") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun InParty(c: AppController, info: PartyInfo) {
    val ui by info.session.ui.collectAsStateCompat()
    val chat by c.party.chat.collectAsStateCompat()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Invite code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(info.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        OutlinedButton(onClick = { PartyManager.copyToClipboard(info.code); c.services.toast("Invite code copied") }) { Text("Copy") }
    }
    if (ui.isHost) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Let everyone play, pause and seek", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = ui.mode == ControlMode.Everyone,
            onCheckedChange = { info.session.setMode(if (it) ControlMode.Everyone else ControlMode.HostOnly) },
        )
    } else if (!ui.canControl(ui.selfId)) Text("The host controls playback.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!ui.connected) Text("Connecting...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (ui.detached) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You are out of sync (paused just for you).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { c.party.rejoin() }) { Text("Rejoin") }
    }
    if (ui.slow) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your stream keeps falling behind.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { info.session.catchUp() }) { Text("Catch up") }
    }
    // Members
    Text("In the room (${ui.members.size})", style = MaterialTheme.typography.labelMedium)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ui.members.forEach { m ->
            val st = ui.statuses[m.id]
            val state = when {
                st == null -> ""
                st.noSource -> "can't play this"
                st.detached -> "paused for themselves"
                st.buffering -> "buffering"
                else -> "ready"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (m.id == ui.hostId) "★ " else "• ") + m.name + if (m.id == ui.selfId) " (you)" else "", modifier = Modifier.weight(1f))
                Text(state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // Chat
    val list = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) list.scrollToItem(chat.size - 1) }
    LazyColumn(
        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.25f)).padding(8.dp),
        state = list, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(chat) { l ->
            if (l.system) Text(l.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text((if (l.self) "You" else l.name) + ": " + l.text, style = MaterialTheme.typography.bodySmall)
        }
    }
    var msg by remember { mutableStateOf("") }
    fun send() { if (msg.isNotBlank()) { info.session.chat(msg); msg = "" } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            msg, { msg = it.take(500) }, singleLine = true, placeholder = { Text("Message") }, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
        )
        OutlinedButton(onClick = { send() }) { Text("Send") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("😂", "😮", "❤", "👍", "🔥").forEach { e ->
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { info.session.react(e) }.padding(horizontal = 8.dp, vertical = 4.dp)) { Text(e, fontSize = 18.sp) }
        }
    }
}

/** Top bar: "Watch party" (join) when not in one; the party's state with Open / Rejoin when in one. */
@Composable
fun PartyChip(c: AppController) {
    val info by c.party.active.collectAsStateCompat()
    var panel by remember { mutableStateOf(false) }
    val i = info
    val label = if (i == null) "Join party" else {
        val ui by i.session.ui.collectAsStateCompat()
        "Party · ${ui.members.size}" + if (ui.detached) " · Rejoin" else ""
    }
    Box(
        Modifier.padding(end = 12.dp).clip(RoundedCornerShape(20.dp))
            .background(if (i != null) Accent.copy(0.85f) else Color.White.copy(0.08f))
            .clickable { if (i != null && i.session.ui.value.detached) c.party.rejoin() else panel = true }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    if (panel) {
        if (i == null) AlertDialog(
            onDismissRequest = { panel = false },
            title = { Text("Join a watch party") },
            text = {
                Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste the invite code a friend sent you. The video opens by itself once you are in.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var name by remember { mutableStateOf(c.party.defaultName()) }
                    NameField(name) { name = it }
                    JoinField(c, name, onJoined = { panel = false })
                    Text("To start one, open a video and press \"Watch party\" in the player.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { panel = false }) { Text("Close") } },
        ) else PartyDialog(c) { panel = false }
    }
}
