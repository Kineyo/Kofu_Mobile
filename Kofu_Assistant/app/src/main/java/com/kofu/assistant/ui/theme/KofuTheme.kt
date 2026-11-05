package com.kofu.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class KofuThemeMode(val label: String, val icon: String) {
    LIGHT("Claro", "☀️"),
    DARK("Oscuro", "🌙"),
    OLED_BLACK("OLED Negro", "🖤"),
    SYSTEM("Sistema", "⚙️")
}

enum class KofuFontScale(val label: String, val bodySp: Float, val codeSp: Float) {
    COMPACT("Compacto", 13f, 11f),
    STANDARD("Estándar", 15f, 13f),
    LARGE("Grande", 17f, 15f)
}

data class KofuColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceSecondary: Color,
    val onSurfaceMuted: Color,
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
    val successAccent: Color,
    val sendButton: Color,
    val sendButtonText: Color,
    val sendButtonDisabled: Color,
    val headerOverlay: Color,
    val divider: Color,
    val codeBlockBackground: Color,
    val codeBlockBorder: Color,
    val isDark: Boolean
)

val DarkKofuColors = KofuColors(
    background = Color(0xFF0D0F18),
    surface = Color(0xFF131724),
    surfaceVariant = Color(0xFF1A1F30),
    surfaceElevated = Color(0xFF22283D),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceSecondary = Color(0xFF94A3B8),
    onSurfaceMuted = Color(0xFF64748B),
    userBubble = Color(0xFF6D28D9),
    userBubbleText = Color(0xFFF8FAFC),
    kofuBubble = Color(0xFF151928),
    kofuBubbleBorder = Color(0xFF262D42),
    kofuText = Color(0xFFE2E8F0),
    inputBackground = Color(0xFF151928),
    inputBorder = Color(0xFF2B334B),
    inputBorderFocused = Color(0xFF8B5CF6),
    accent = Color(0xFF8B5CF6),
    accentSecondary = Color(0xFFA78BFA),
    accentSoft = Color(0xFF2E1D59),
    warningAccent = Color(0xFFF59E0B),
    errorAccent = Color(0xFFEF4444),
    successAccent = Color(0xFF10B981),
    sendButton = Color(0xFF8B5CF6),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFF252A3C),
    headerOverlay = Color(0xFF0D0F18),
    divider = Color(0xFF22283A),
    codeBlockBackground = Color(0xFF0A0C13),
    codeBlockBorder = Color(0xFF2A3147),
    isDark = true
)

// Modo OLED Pure Black especial para pantallas AMOLED de alta gama (Samsung S21+, etc.)
val OledBlackKofuColors = KofuColors(
    background = Color(0xFF000000),
    surface = Color(0xFF090A0E),
    surfaceVariant = Color(0xFF12141A),
    surfaceElevated = Color(0xFF1A1D26),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceSecondary = Color(0xFFA1A1AA),
    onSurfaceMuted = Color(0xFF71717A),
    userBubble = Color(0xFF6D28D9),
    userBubbleText = Color(0xFFFFFFFF),
    kofuBubble = Color(0xFF0D0E14),
    kofuBubbleBorder = Color(0xFF27272A),
    kofuText = Color(0xFFE4E4E7),
    inputBackground = Color(0xFF0A0B10),
    inputBorder = Color(0xFF27272A),
    inputBorderFocused = Color(0xFF8B5CF6),
    accent = Color(0xFF8B5CF6),
    accentSecondary = Color(0xFFA78BFA),
    accentSoft = Color(0xFF241547),
    warningAccent = Color(0xFFFBBF24),
    errorAccent = Color(0xFFF87171),
    successAccent = Color(0xFF34D399),
    sendButton = Color(0xFF8B5CF6),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFF1F2129),
    headerOverlay = Color(0xFF000000),
    divider = Color(0xFF1E2028),
    codeBlockBackground = Color(0xFF050508),
    codeBlockBorder = Color(0xFF22242E),
    isDark = true
)

val LightKofuColors = KofuColors(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    surfaceElevated = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    onSurfaceSecondary = Color(0xFF64748B),
    onSurfaceMuted = Color(0xFF94A3B8),
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
    successAccent = Color(0xFF059669),
    sendButton = Color(0xFF6D28D9),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFFE2E8F0),
    headerOverlay = Color(0xFFF8FAFC),
    divider = Color(0xFFE2E8F0),
    codeBlockBackground = Color(0xFFF8FAFC),
    codeBlockBorder = Color(0xFFCBD5E1),
    isDark = false
)

object KofuDesignSystem {
    // Bordes modernos menos redondeados (8dp - 12dp en lugar de 50dp pills)
    val ButtonShape = RoundedCornerShape(8.dp)
    val PillShape = RoundedCornerShape(8.dp)
    val CardShape = RoundedCornerShape(12.dp)
    val InputShape = RoundedCornerShape(10.dp)
    val DialogShape = RoundedCornerShape(14.dp)
    val UserBubbleShape = RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp)
    val KofuBubbleShape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp)
}

val LocalKofuColors = staticCompositionLocalOf { DarkKofuColors }
val LocalKofuFontScale = staticCompositionLocalOf { KofuFontScale.STANDARD }

@Composable
fun KofuTheme(
    themeMode: KofuThemeMode = KofuThemeMode.SYSTEM,
    fontScale: KofuFontScale = KofuFontScale.STANDARD,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val kofuColors = when (themeMode) {
        KofuThemeMode.LIGHT -> LightKofuColors
        KofuThemeMode.DARK -> DarkKofuColors
        KofuThemeMode.OLED_BLACK -> OledBlackKofuColors
        KofuThemeMode.SYSTEM -> if (systemDark) DarkKofuColors else LightKofuColors
    }

    val materialColorScheme = if (kofuColors.isDark) {
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

    val modernShapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp)
    )

    CompositionLocalProvider(
        LocalKofuColors provides kofuColors,
        LocalKofuFontScale provides fontScale
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            shapes = modernShapes,
            content = content
        )
    }
}
