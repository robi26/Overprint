package net.roz.connectstats.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import net.roz.connectstats.data.prefs.AppSettings

fun Long.toComposeColor(): Color = Color(toInt())

val Highlight = Color(0xFF3583F3)
val Navy = Color(0xFF0B1B33)
val NavyCard = Color(0xFF13243F)
val EvenRow = Color(0xFF1A2E4F)

val DarkWindowArgb = 0xFF0B1B33.toInt()
val LightWindowArgb = 0xFFF6F3F1.toInt()

private val DarkColors = darkColorScheme(
    primary = Highlight,
    onPrimary = Color.White,
    secondary = Color(0xFF5CE6B8),
    tertiary = Color(0xFF7C8CFF),
    background = Navy,
    surface = NavyCard,
    onBackground = Color(0xFFF4F7FB),
    onSurface = Color(0xFFF4F7FB),
    onSurfaceVariant = Color(0xFFB7C3D6),
    outline = Color(0xFF3A4E6E),
    surfaceVariant = EvenRow,
)

private val LightColors = lightColorScheme(
    primary = Highlight,
    onPrimary = Color.White,
    secondary = Color(0xFF1AA6C4),
    background = Color(0xFFF6F3F1),
    surface = Color.White,
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFD2D2D2),
    surfaceVariant = Color(0xFFE7EDF5),
)

fun AppSettings.resolvedDarkTheme(systemDark: Boolean = false): Boolean = when (themeMode) {
    AppSettings.THEME_LIGHT -> false
    AppSettings.THEME_DARK -> true
    else -> systemDark
}

@Composable
fun OverprintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
