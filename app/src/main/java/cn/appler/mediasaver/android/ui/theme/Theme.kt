package cn.appler.mediasaver.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
  lightColorScheme(
    primary = Color(0xFFD9482B),
    onPrimary = Color(0xFFFFFAF7),
    primaryContainer = Color(0xFFFBE8E1),
    onPrimaryContainer = Color(0xFF3A160F),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF211D1A),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF211D1A),
    surfaceVariant = Color(0xFFF0ECE4),
    onSurfaceVariant = Color(0xFF7A7168),
    outline = Color(0xFFE6DFD4),
  )

private val DarkColors =
  darkColorScheme(
    primary = Color(0xFFFF7A5C),
    onPrimary = Color(0xFF24110C),
    primaryContainer = Color(0xFF3A211A),
    onPrimaryContainer = Color(0xFFF3EDE6),
    background = Color(0xFF141210),
    onBackground = Color(0xFFF3EDE6),
    surface = Color(0xFF1D1916),
    onSurface = Color(0xFFF3EDE6),
    surfaceVariant = Color(0xFF27221D),
    onSurfaceVariant = Color(0xFFA89C90),
    outline = Color(0xFF342D26),
  )

@Composable
fun MediaSaverTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
