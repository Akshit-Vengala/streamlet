package cloudstream.desktop.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.contracts.WatchStatus

/** One row of a card's right-click menu; [divider] draws a line above it. */
data class CardMenuItem(
    val label: String, val checked: Boolean = false, val destructive: Boolean = false, val divider: Boolean = false,
    val onClick: () -> Unit,
)

/** The menu, opened at [at] (pointer position inside the card, in px). */
@Composable
fun CardMenu(expanded: Boolean, at: Offset, items: List<CardMenuItem>, onDismiss: () -> Unit) {
    // A zero-size anchor at the click point: the dropdown opens right where the user clicked.
    Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }.size(0.dp)) {
        DropdownMenu(expanded, onDismiss, containerColor = Color(0xFF202027)) {
            items.forEach { item ->
                if (item.divider) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (item.checked) "✓  " else "     ") + item.label,
                            color = if (item.destructive) Color(0xFFFF8A80) else Color.White,
                            fontWeight = if (item.checked) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = { onDismiss(); item.onClick() },
                )
            }
        }
    }
}

/**
 * CloudStream's library actions for a title: the watch statuses (current one ticked) and "Remove from library";
 * on a Continue Watching card also "Remove from Continue Watching" (first).
 */
fun libraryMenu(c: AppController, card: MediaCard, continueWatching: Boolean = false): List<CardMenuItem> {
    val current = c.library.state.value.statusOf(card)
    val out = ArrayList<CardMenuItem>()
    if (continueWatching) out += CardMenuItem("Remove from Continue Watching", destructive = true) { c.removeFromContinueWatching(card) }
    WatchStatus.entries.forEachIndexed { i, s ->
        out += CardMenuItem(s.label, checked = s == current, divider = continueWatching && i == 0) { c.setWatchStatus(card, s) }
    }
    if (current != null) out += CardMenuItem("Remove from library", destructive = true, divider = true) { c.setWatchStatus(card, null) }
    return out
}
