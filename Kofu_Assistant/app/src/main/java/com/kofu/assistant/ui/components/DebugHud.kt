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
import androidx.compose.material.icons.filled.Info
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
            color = colors.surface.copy(alpha = 0.95f),
            shape = KofuDesignSystem.CardShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(KofuDesignSystem.CardShape)
                .border(1.dp, colors.accent.copy(alpha = 0.35f), KofuDesignSystem.CardShape)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
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
                                .size(8.dp)
                                .clip(KofuDesignSystem.ButtonShape)
                                .background(colors.successAccent)
                        )
                        Text(
                            text = "HUD DE DEPURACIÓN (LIVE)",
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Inspeccionar",
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable { showRawInspector = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar HUD",
                                tint = colors.onSurfaceSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Métricas clave en fila moderna
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(
                        label = "Velocidad",
                        value = if (metrics.tokensPerSec > 0) String.format("%.1f t/s", metrics.tokensPerSec) else "-- t/s"
                    )
                    MetricItem(
                        label = "TTFT",
                        value = if (metrics.latencyFirstTokenMs > 0) "${metrics.latencyFirstTokenMs} ms" else "-- ms"
                    )
                    MetricItem(
                        label = "Tokens",
                        value = "${metrics.tokensGenerated}"
                    )
                    MetricItem(
                        label = "Total",
                        value = if (metrics.totalDurationMs > 0) String.format("%.1f s", metrics.totalDurationMs / 1000.0) else "-- s"
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Motor: ${metrics.activeBackend}",
                        color = colors.onSurfaceSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Modelo: ${metrics.activeModel}",
                        color = colors.onSurfaceSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                    text = "Inspección de Inferencia",
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
            },
            text = {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PROMPT MULTI-TURNO COMPILADO:",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
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
                        text = "ÚLTIMA RESPUESTA RECIBIDA:",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
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
                    Text("Cerrar", color = colors.accent)
                }
            },
            containerColor = colors.surface
        )
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    val colors = LocalKofuColors.current
    Column {
        Text(text = label, color = colors.onSurfaceMuted, fontSize = 9.sp)
        Text(
            text = value,
            color = colors.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
