package com.debdroid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.debdroid.app.prefs.ThemeMode

/**
 * v2 主题：电光蓝 #0A84FF（tech-innovation 系，与 mockup 设计令牌一致）。
 * 深色为主基调；浅色模式仅作跟随系统选项。
 */
private val Blue = Color(0xFF0A84FF)
private val BlueContainer = Color(0xFF0A2E4D)
private val OnBlueContainer = Color(0xFFCFE8FF)
private val Green = Color(0xFF3FB950)
private val SurfaceDark = Color(0xFF101418)
private val SurfaceContainerDark = Color(0xFF171D24)
private val OnSurfaceDark = Color(0xFFE6EDF3)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = OnBlueContainer,
    secondary = Green,
    onSecondary = Color(0xFF06120A),
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = Color(0xFF9AA7B4),
    outline = Color(0xFF6E7B88),
    outlineVariant = Color(0xFF2A3440),
    error = Color(0xFFF85149),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE8FF),
    onPrimaryContainer = Color(0xFF002B52),
    secondary = Color(0xFF1F7A3A),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF171D24),
    onSurfaceVariant = Color(0xFF4A5560),
    error = Color(0xFFB3261E),
)

@Composable
fun DebDroidTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
