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

enum class KofuThemeMode(val label: String) {
    DARK("Oscuro"),
    OLED_BLACK("OLED Negro"),
    LIGHT("Claro"),
    SYSTEM("Sistema")
}

enum class KofuFontScale(val label: String, val bodySp: Float, val codeSp: Float) {
    COMPACT("Compacto", 12.5f, 11f),
    STANDARD("Estándar", 14f, 12f),
    LARGE("Grande", 15.5f, 13.5f)
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

// Tema formal en negro profundo (Obsidian Monochrome)
val DarkKofuColors = KofuColors(
    background = Color(0xFF070709),
    surface = Color(0xFF0F0F12),
    surfaceVariant = Color(0xFF15151A),
    surfaceElevated = Color(0xFF1C1C22),
    onSurface = Color(0xFFEEEEF2),
    onSurfaceSecondary = Color(0xFF90929C),
    onSurfaceMuted = Color(0xFF5A5C66),
    userBubble = Color(0xFF1E2028),
    userBubbleText = Color(0xFFFFFFFF),
    kofuBubble = Color(0xFF0D0D10),
    kofuBubbleBorder = Color(0xFF202026),
    kofuText = Color(0xFFE2E4EC),
    inputBackground = Color(0xFF0B0B0E),
    inputBorder = Color(0xFF22222A),
    inputBorderFocused = Color(0xFF4C5062),
    accent = Color(0xFFEEEEF2),
    accentSecondary = Color(0xFFA0A4B8),
    accentSoft = Color(0xFF1E202A),
    warningAccent = Color(0xFFD97706),
    errorAccent = Color(0xFFDC2626),
    successAccent = Color(0xFF10B981),
    sendButton = Color(0xFFEEEEF2),
    sendButtonText = Color(0xFF070709),
    sendButtonDisabled = Color(0xFF1E1E24),
    headerOverlay = Color(0xFF070709),
    divider = Color(0xFF18181E),
    codeBlockBackground = Color(0xFF050507),
    codeBlockBorder = Color(0xFF1F1F26),
    isDark = true
)

// Tema OLED Pure Black (100% negro absoluto para paneles AMOLED)
val OledBlackKofuColors = KofuColors(
    background = Color(0xFF000000),
    surface = Color(0xFF08080A),
    surfaceVariant = Color(0xFF101014),
    surfaceElevated = Color(0xFF17171C),
    onSurface = Color(0xFFF4F4F6),
    onSurfaceSecondary = Color(0xFF8E909A),
    onSurfaceMuted = Color(0xFF555760),
    userBubble = Color(0xFF18181E),
    userBubbleText = Color(0xFFFFFFFF),
    kofuBubble = Color(0xFF070709),
    kofuBubbleBorder = Color(0xFF1D1D24),
    kofuText = Color(0xFFE0E2EA),
    inputBackground = Color(0xFF050507),
    inputBorder = Color(0xFF1E1E24),
    inputBorderFocused = Color(0xFF484B58),
    accent = Color(0xFFFFFFFF),
    accentSecondary = Color(0xFF989BAC),
    accentSoft = Color(0xFF16161C),
    warningAccent = Color(0xFFD97706),
    errorAccent = Color(0xFFDC2626),
    successAccent = Color(0xFF10B981),
    sendButton = Color(0xFFFFFFFF),
    sendButtonText = Color(0xFF000000),
    sendButtonDisabled = Color(0xFF16161A),
    headerOverlay = Color(0xFF000000),
    divider = Color(0xFF141418),
    codeBlockBackground = Color(0xFF030304),
    codeBlockBorder = Color(0xFF1A1A20),
    isDark = true
)

// Tema Claro sobrio y formal
val LightKofuColors = KofuColors(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEEF2),
    surfaceElevated = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111216),
    onSurfaceSecondary = Color(0xFF5C5E68),
    onSurfaceMuted = Color(0xFF8B8E9A),
    userBubble = Color(0xFF18191E),
    userBubbleText = Color(0xFFFFFFFF),
    kofuBubble = Color(0xFFFFFFFF),
    kofuBubbleBorder = Color(0xFFE0E2EA),
    kofuText = Color(0xFF18191E),
    inputBackground = Color(0xFFFFFFFF),
    inputBorder = Color(0xFFD4D6E0),
    inputBorderFocused = Color(0xFF18191E),
    accent = Color(0xFF18191E),
    accentSecondary = Color(0xFF383A46),
    accentSoft = Color(0xFFE8E9F0),
    warningAccent = Color(0xFFB45309),
    errorAccent = Color(0xFFB91C1C),
    successAccent = Color(0xFF047857),
    sendButton = Color(0xFF18191E),
    sendButtonText = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0xFFE0E2EA),
    headerOverlay = Color(0xFFF6F7F9),
    divider = Color(0xFFE5E7EE),
    codeBlockBackground = Color(0xFFF0F1F5),
    codeBlockBorder = Color(0xFFD4D6E0),
    isDark = false
)

object KofuDesignSystem {
    // Bordes formales, arquitectónicos y compactos (6dp a 8dp)
    val ButtonShape = RoundedCornerShape(6.dp)
    val PillShape = RoundedCornerShape(6.dp)
    val CardShape = RoundedCornerShape(8.dp)
    val InputShape = RoundedCornerShape(8.dp)
    val DialogShape = RoundedCornerShape(10.dp)
    val UserBubbleShape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
    val KofuBubbleShape = RoundedCornerShape(8.dp, 8.dp, 8.dp, 2.dp)
}

val LocalKofuColors = staticCompositionLocalOf { DarkKofuColors }
val LocalKofuFontScale = staticCompositionLocalOf { KofuFontScale.STANDARD }

@Composable
fun KofuTheme(
    themeMode: KofuThemeMode = KofuThemeMode.DARK,
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
            onPrimary = Color.Black,
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

    val formalShapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(10.dp),
        extraLarge = RoundedCornerShape(12.dp)
    )

    CompositionLocalProvider(
        LocalKofuColors provides kofuColors,
        LocalKofuFontScale provides fontScale
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            shapes = formalShapes,
            content = content
        )
    }
}
