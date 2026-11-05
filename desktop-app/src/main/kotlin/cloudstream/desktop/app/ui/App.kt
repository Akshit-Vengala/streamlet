package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat() = collectAsState()

/** Brand red (accent for focus, progress, the logo). */
val Accent = Color(0xFFE50914)
val Ink = Color(0xFF0B0B0E)

val CloudDark: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0D10),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A31),
    onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFF46D369),
    background = Ink,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141418),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF22222A),
    onSurfaceVariant = Color(0xFFA6A6B0),
    surfaceContainer = Color(0xFF18181D),
    surfaceContainerHigh = Color(0xFF1E1E24),
    surfaceContainerHighest = Color(0xFF25252C),
    outline = Color(0xFF4A4A55),
    outlineVariant = Color(0xFF2C2C33),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val base = Typography()
private val CloudType = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val CloudShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun CloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CloudDark, typography = CloudType, shapes = CloudShapes, content = content)
}

/** Height of the floating top bar; screens without a full-bleed hero pad their content by this much. */
val TopBarHeight = 64.dp

private data class NavItem(val screen: Screen, val label: String)

private val navItems = listOf(
    NavItem(Screen.Home, "Home"),
    NavItem(Screen.Search, "Search"),
    NavItem(Screen.Library, "My List"),
    NavItem(Screen.Downloads, "Downloads"),
    NavItem(Screen.Extensions, "Extensions"),
    NavItem(Screen.Settings, "Settings"),
)

@Composable
fun AppRoot(c: AppController, searchFocus: FocusRequester = remember { FocusRequester() }) {
    CloudTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val stack by c.nav.stack.collectAsStateCompat()
            val current = stack.last()
            val snack = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { c.services.toasts.collect { snack.showSnackbar(it) } }
            val profile by c.services.profiles.current.collectAsStateCompat()
            var picking by remember { mutableStateOf(c.services.profiles.askOnStart) }
            if (picking) { ProfilePicker(c) { picking = false }; return@Surface }

            // Keyed on the profile: screens re-read the per-profile settings and library after a switch.
            key(profile.id) {
                Box(Modifier.fillMaxSize()) {
                    when (val s = current) {
                        Screen.Home -> HomeScreen(c)
                        Screen.Search -> SearchScreen(c, searchFocus)
                        Screen.Library -> LibraryScreen(c)
                        Screen.Downloads -> DownloadsScreen(c)
                        Screen.Extensions -> ExtensionsScreen(c)
                        Screen.Settings -> SettingsScreen(c)
                        is Screen.Details -> DetailsScreen(c, c.detailsVm(s.card))
                        is Screen.Player -> PlayerScreen(c, s.request)
                    }
                    if (current !is Screen.Player) TopBar(c, stack.first())
                    Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { SnackbarHost(snack) }
                }
            }
        }
    }
}

/** Floating top bar: logo, sections, profile. Fades from black so it can sit over the hero artwork. */
@Composable
private fun TopBar(c: AppController, root: Screen) {
    Row(
        Modifier.fillMaxWidth().height(TopBarHeight)
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.92f), Ink.copy(alpha = 0.55f), Color.Transparent)))
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogoImage(30.dp, Modifier.clickable { c.goTo(Screen.Home) })
            navItems.forEach { item -> NavLink(item.label, root == item.screen) { c.goTo(item.screen) } }
        }
        ProfileRailButton(c)
    }
}

@Composable
private fun NavLink(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Text(
        label,
        Modifier.hoverable(src).clickable(onClick = onClick),
        color = when { selected -> Color.White; hovered -> Color(0xFFD0D0D0); else -> Color(0xFF9A9AA2) },
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 15.sp,
    )
}
