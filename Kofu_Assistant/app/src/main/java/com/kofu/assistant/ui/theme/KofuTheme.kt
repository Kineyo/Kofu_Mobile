package com.kofu.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class PromeiaColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceSecondary: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val promeiaText: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val inputBorderFocused: Color,
    val accent: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val headerOverlay: Color,
    val divider: Color
)

val DarkPromeiaColors = PromeiaColors(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceSecondary = Color(0xFF888888),
    userBubble = Color(0xFF2A2A2A),
    userBubbleText = Color(0xFFE8E8E8),
    promeiaText = Color(0xFFCCCCCC),
    inputBackground = Color(0xFF1E1E1E),
    inputBorder = Color(0xFF333333),
    inputBorderFocused = Color(0xFF555555),
    accent = Color(0xFFE0E0E0),
    sendButton = Color(0xFFE0E0E0),
    sendButtonDisabled = Color(0xFF333333),
    headerOverlay = Color(0xFF0A0A0A),
    divider = Color(0xFF252525)
)

val LightPromeiaColors = PromeiaColors(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceSecondary = Color(0xFF666666),
    userBubble = Color(0xFFE0E0E0),
    userBubbleText = Color(0xFF1A1A1A),
    promeiaText = Color(0xFF2A2A2A),
    inputBackground = Color(0xFFF0F0F0),
    inputBorder = Color(0xFFCCCCCC),
    inputBorderFocused = Color(0xFF999999),
    accent = Color(0xFF333333),
    sendButton = Color(0xFF333333),
    sendButtonDisabled = Color(0xFFCCCCCC),
    headerOverlay = Color(0xFFFAFAFA),
    divider = Color(0xFFE5E5E5)
)

val LocalPromeiaColors = staticCompositionLocalOf { DarkPromeiaColors }

@Composable
fun KofuTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val promeiaColors = if (isDarkTheme) DarkPromeiaColors else LightPromeiaColors

    val materialColorScheme = if (isDarkTheme) {
        darkColorScheme(
            background = promeiaColors.background,
            surface = promeiaColors.surface,
            onBackground = promeiaColors.onSurface,
            onSurface = promeiaColors.onSurface
        )
    } else {
        lightColorScheme(
            background = promeiaColors.background,
            surface = promeiaColors.surface,
            onBackground = promeiaColors.onSurface,
            onSurface = promeiaColors.onSurface
        )
    }

    CompositionLocalProvider(LocalPromeiaColors provides promeiaColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content
        )
    }
}
