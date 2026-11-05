package com.kofu.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofu.assistant.repository.DebugMetrics
import com.kofu.assistant.ui.theme.KofuDesignSystem
import com.kofu.assistant.ui.theme.LocalKofuColors

@Composable
fun DebugHud(
    metrics: DebugMetrics,
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKofuColors.current
    var showRawInspector by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            color = colors.surface,
            shape = KofuDesignSystem.CardShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(KofuDesignSystem.CardShape)
                .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                // Barra superior de estado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(KofuDesignSystem.ButtonShape)
                                .background(colors.successAccent)
                        )
                        Text(
                            text = "TELEMETRÍA",
                            color = colors.onSurfaceSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RAW",
                            color = colors.onSurfaceSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable { showRawInspector = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )

                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = colors.onSurfaceMuted,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onClose() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Fila de métricas compactas en formato monospace formal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (metrics.tokensPerSec > 0) String.format("%.1f t/s", metrics.tokensPerSec) else "-- t/s",
                        color = colors.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "•",
                        color = colors.onSurfaceMuted,
                        fontSize = 10.sp
                    )

                    Text(
                        text = if (metrics.latencyFirstTokenMs > 0) "${metrics.latencyFirstTokenMs}ms" else "-- ms",
                        color = colors.onSurfaceSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "•",
                        color = colors.onSurfaceMuted,
                        fontSize = 10.sp
                    )

                    Text(
                        text = "${metrics.tokensGenerated} tok",
                        color = colors.onSurfaceSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "•",
                        color = colors.onSurfaceMuted,
                        fontSize = 10.sp
                    )

                    Text(
                        text = if (metrics.totalDurationMs > 0) String.format("%.1fs", metrics.totalDurationMs / 1000.0) else "-- s",
                        color = colors.onSurfaceSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "•",
                        color = colors.onSurfaceMuted,
                        fontSize = 10.sp
                    )

                    Text(
                        text = metrics.activeModel.ifEmpty { "Inactivo" },
                        color = colors.accentSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showRawInspector) {
        AlertDialog(
            onDismissRequest = { showRawInspector = false },
            title = {
                Text(
                    text = "Inspección Raw",
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PROMPT ENVIADO",
                        color = colors.onSurfaceSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Surface(
                        color = colors.codeBlockBackground,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.codeBlockBorder, KofuDesignSystem.ButtonShape)
                    ) {
                        Text(
                            text = metrics.lastRawPrompt.ifEmpty { "(Sin prompt registrado)" },
                            color = colors.onSurface,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "RESPUESTA RAW",
                        color = colors.onSurfaceSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Surface(
                        color = colors.codeBlockBackground,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.codeBlockBorder, KofuDesignSystem.ButtonShape)
                    ) {
                        Text(
                            text = metrics.lastRawResponse.ifEmpty { "(Sin respuesta registrada)" },
                            color = colors.onSurface,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRawInspector = false }) {
                    Text("Cerrar", color = colors.onSurface, fontSize = 12.sp)
                }
            },
            containerColor = colors.surface,
            shape = KofuDesignSystem.DialogShape
        )
    }
}
