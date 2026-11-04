package com.kofu.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class KofuThemeMode(val label: String) {
    LIGHT("Claro"),
    DARK("Oscuro"),
    SYSTEM("Sistema")
}

data class KofuColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceSecondary: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val kofuBubble: Color,
    val kofuBubbleBorder: Color,
    val kofuText: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val inputBorderFocused: Color,
    val accent: Color,
    val accentSecondary: Color,
    val accentSoft: Color,
    val warningAccent: Color,
    val errorAccent: Color,
    val sendButton: Color,
    val sendButtonText: Color,
    val sendButtonDisabled: Color,
    val headerOverlay: Color,
    val divider: Color,
    val isDark: Boolean
)

val DarkKofuColors = KofuColors(
    background = Color(0xFF090A0F),
    surface = Color(0xFF12141D),
    surfaceVariant = Color(0xFF1B1E2B),
    surfaceElevated = Color(0xFF222637),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceSecondary = Color(0xFF94A3B8),
    userBubble = Color(0xFF6D28D9),
    userBubbleText = Color(0xFFF8FAFC),
    kofuBubble = Color(0xFF151824),
    kofuBubbleBorder = Color(0xFF282D42),
    kofuText = Color(0xFFE2E8F0),
    inputBackground = Color(0xFF161925),
    inputBorder = Color(0xFF2A2F45),
    inputBorderFocused = Color(0xFF8B5CF6),
    accent = Color(0xFF8B5CF6),
    accentSecondary = Color(0xFFA78BFA),
    accentSoft = Color(0xFF2E1D59),
    warningAccent = Color(0xFFF59E0B),
    errorAccent = Color(0xFFEF4444),
    sendButton = Color(0xFF8B5CF6),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFF262A3B),
    headerOverlay = Color(0xFF090A0F),
    divider = Color(0xFF232738),
    isDark = true
)

val LightKofuColors = KofuColors(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    surfaceElevated = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    onSurfaceSecondary = Color(0xFF64748B),
    userBubble = Color(0xFF6D28D9),
    userBubbleText = Color(0xFFFFFFFF),
    kofuBubble = Color(0xFFFFFFFF),
    kofuBubbleBorder = Color(0xFFE2E8F0),
    kofuText = Color(0xFF1E293B),
    inputBackground = Color(0xFFF1F5F9),
    inputBorder = Color(0xFFCBD5E1),
    inputBorderFocused = Color(0xFF6D28D9),
    accent = Color(0xFF6D28D9),
    accentSecondary = Color(0xFF7C3AED),
    accentSoft = Color(0xFFEDE9FE),
    warningAccent = Color(0xFFD97706),
    errorAccent = Color(0xFFDC2626),
    sendButton = Color(0xFF6D28D9),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFFE2E8F0),
    headerOverlay = Color(0xFFF8FAFC),
    divider = Color(0xFFE2E8F0),
    isDark = false
)

val LocalKofuColors = staticCompositionLocalOf { DarkKofuColors }

@Composable
fun KofuTheme(
    themeMode: KofuThemeMode = KofuThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        KofuThemeMode.LIGHT -> false
        KofuThemeMode.DARK -> true
        KofuThemeMode.SYSTEM -> systemDark
    }
    KofuTheme(isDarkTheme = useDarkTheme, content = content)
}

@Composable
fun KofuTheme(
    isDarkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val kofuColors = if (isDarkTheme) DarkKofuColors else LightKofuColors

    val materialColorScheme = if (isDarkTheme) {
        darkColorScheme(
            primary = kofuColors.accent,
            secondary = kofuColors.accentSecondary,
            background = kofuColors.background,
            surface = kofuColors.surface,
            surfaceVariant = kofuColors.surfaceVariant,
            onPrimary = Color.White,
            onBackground = kofuColors.onSurface,
            onSurface = kofuColors.onSurface,
            error = kofuColors.errorAccent
        )
    } else {
        lightColorScheme(
            primary = kofuColors.accent,
            secondary = kofuColors.accentSecondary,
            background = kofuColors.background,
            surface = kofuColors.surface,
            surfaceVariant = kofuColors.surfaceVariant,
            onPrimary = Color.White,
            onBackground = kofuColors.onSurface,
            onSurface = kofuColors.onSurface,
            error = kofuColors.errorAccent
        )
    }

    CompositionLocalProvider(LocalKofuColors provides kofuColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content
        )
    }
}
