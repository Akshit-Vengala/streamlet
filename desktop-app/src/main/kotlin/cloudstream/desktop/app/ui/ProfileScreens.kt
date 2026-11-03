package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.Profile
import cloudstream.desktop.app.vm.Screen

@Composable
fun ProfileAvatar(p: Profile, size: Dp = 40.dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(p.color))
            .let { if (selected) it.background(Color(p.color)) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(p.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}

/** Full-screen "who's watching" picker shown at start-up when there is more than one profile. */
@Composable
fun ProfilePicker(c: AppController, onPicked: () -> Unit) {
    val profiles by c.services.profiles.list.collectAsStateCompat()
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Who's watching?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer8(); Spacer8()
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            profiles.forEach { p ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { c.switchProfile(p.id); onPicked() }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProfileAvatar(p, 96.dp)
                    Spacer8()
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Rail button: current profile; click to switch or manage. */
@Composable
fun ProfileRailButton(c: AppController) {
    val profiles by c.services.profiles.list.collectAsStateCompat()
    val cur by c.services.profiles.current.collectAsStateCompat()
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        ProfileAvatar(cur, 36.dp) { open = true }
        DropdownMenu(open, { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text((if (p.id == cur.id) "> " else "") + p.name) },
                    leadingIcon = { ProfileAvatar(p, 24.dp) },
                    onClick = { open = false; c.switchProfile(p.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Manage profiles...") }, onClick = { open = false; c.goTo(Screen.Settings) })
        }
    }
}

/** Settings section: create, rename, delete, switch; whether to ask at start-up. */
@Composable
fun ProfilesSection(c: AppController) {
    val pm = c.services.profiles
    val profiles by pm.list.collectAsStateCompat()
    val cur by pm.current.collectAsStateCompat()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var askOnStart by remember { mutableStateOf(pm.askOnStart || profiles.size <= 1) }

    SectionCard {
        Text("Profiles", fontWeight = FontWeight.SemiBold)
        Text(
            "Each profile has its own bookmarks, history, resume points and settings (quality, source priority, subtitles). " +
                "Extensions and repositories are shared.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer8()
        profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(p, 32.dp)
                Text(p.name + if (p.id == cur.id) "  (current)" else "", Modifier.weight(1f))
                if (p.id != cur.id) TextButton(onClick = { c.switchProfile(p.id) }) { Text("Switch") }
                TextButton(onClick = { renaming = p }) { Text("Rename") }
                if (profiles.size > 1) TextButton(onClick = { deleting = p }) { Text("Delete") }
            }
        }
        Spacer8()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("New profile name") }, modifier = Modifier.width(260.dp))
            Button(onClick = { pm.create(newName); newName = "" }) { Text("Add profile") }
        }
        if (profiles.size > 1) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Ask who's watching at start-up")
            Switch(askOnStart, { askOnStart = it; pm.setAskOnStart(it) })
        }
    }

    renaming?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename profile") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { pm.rename(p.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Its bookmarks, history, resume points and settings are deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val wasCurrent = p.id == cur.id
                    if (wasCurrent) profiles.firstOrNull { it.id != p.id }?.let { c.switchProfile(it.id) }
                    pm.delete(p.id); deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
