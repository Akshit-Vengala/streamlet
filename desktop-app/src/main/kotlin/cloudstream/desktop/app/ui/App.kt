package cloudstream.desktop.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
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
                    // A season download's episode without the chosen source: pick another (shown over any screen but the player).
                    val prompts by c.seasonDownloads.prompts.collectAsStateCompat()
                    prompts.firstOrNull()?.takeIf { current !is Screen.Player }?.let { p ->
                        androidx.compose.runtime.key(p.id) {
                            DownloadDialog(c, p.card, p.details, p.episode, prompt = p) { c.seasonDownloads.dismiss(p) }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { SnackbarHost(snack) }
                }
            }
        }
    }
}

/** Shown in the window while the services start in the background (or the message when they could not). */
@Composable
fun StartupSplash(error: String?) {
    CloudTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                AppLogoImage(72.dp)
                Spacer(Modifier.height(18.dp))
                Text("Streamlet", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Spacer(Modifier.height(22.dp))
                if (error == null) androidx.compose.material3.LinearProgressIndicator(Modifier.width(180.dp), color = Accent, trackColor = Color.White.copy(0.12f))
                else Text(error, color = Color(0xFFFF8A80), fontSize = 14.sp)
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
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogoImage(30.dp, Modifier.clip(RoundedCornerShape(6.dp)).clickable { c.goTo(Screen.Home) })
            Spacer(Modifier.width(18.dp))
            navItems.forEach { item -> NavLink(item.label, root == item.screen) { c.goTo(item.screen) } }
        }
        ProfileRailButton(c)
    }
}

/** A pill: soft highlight on hover, a filled pill with an accent underline when it is the current section. */
@Composable
private fun NavLink(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(when { selected -> Color.White.copy(0.14f); hovered -> Color.White.copy(0.08f); else -> Color.Transparent }, tween(150))
    val fg by animateColorAsState(when { selected -> Color.White; hovered -> Color(0xFFE6E6EA); else -> Color(0xFFA0A0AA) }, tween(150))
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg)
            .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp)
        if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Accent))
    }
}
