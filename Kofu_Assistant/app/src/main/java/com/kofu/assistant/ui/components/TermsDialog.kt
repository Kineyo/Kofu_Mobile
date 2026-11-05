package com.kofu.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kofu.assistant.ui.theme.KofuDesignSystem
import com.kofu.assistant.ui.theme.LocalKofuColors

@Composable
fun TermsDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val colors = LocalKofuColors.current
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = KofuDesignSystem.DialogShape,
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(KofuDesignSystem.DialogShape)
                .border(1.dp, colors.divider, KofuDesignSystem.DialogShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // CABECERA FIJA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = colors.accentSoft,
                            shape = KofuDesignSystem.ButtonShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Términos y Privacidad",
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "Kofu Assistant • Licencia Gemma",
                                color = colors.onSurfaceSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = colors.onSurfaceSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // CONTENIDO DESPLAZABLE
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Privacidad 100% Local
                    TermCard(
                        icon = Icons.Default.Lock,
                        iconTint = colors.successAccent,
                        title = "1. Privacidad y Soberanía Local",
                        content = "Kofu procesa tus conversaciones exclusivamente de manera local en tu dispositivo o a través de tu servidor Ollama privado en tu red doméstica. Ningún mensaje, prompt, dato personal o telemetría se transmite a la nube ni a servidores externos de terceros. Eres el dueño absoluto de tu información."
                    )

                    // Section 2: Términos y Licencia de Google Gemma
                    TermCard(
                        icon = Icons.Default.Info,
                        iconTint = colors.accent,
                        title = "2. Modelo Base y Términos Gemma",
                        content = "Kofu está construido y optimizado sobre la familia de modelos abiertos Gemma de Google DeepMind. El uso de los pesos y las inferencias está sujeto a los Términos de Uso de Gemma (Gemma Terms of Use) y la Licencia Apache 2.0 en componentes de software libre aplicables. Queda garantizado el derecho a uso personal, educativo y comercial conforme a dichos términos."
                    )

                    // Section 3: Política de Uso Prohibido y Responsable
                    TermCard(
                        icon = Icons.Default.Warning,
                        iconTint = colors.warningAccent,
                        title = "3. Política de Uso Prohibido",
                        content = "De conformidad con los lineamientos de Google DeepMind y las directrices de IA responsable, está estrictamente prohibido utilizar Kofu para:\n" +
                                "• Generar contenido ilícito, violento, acoso o incitación al odio.\n" +
                                "• Crear código malicioso, exploits o ciberataques contra sistemas ajenos.\n" +
                                "• Difundir desinformación deliberada o material médico/financiero sin validación experta.\n" +
                                "• Infringir derechos de privacidad o propiedad intelectual de terceros."
                    )

                    // Section 4: Exención de Responsabilidad
                    TermCard(
                        icon = Icons.Default.Info,
                        iconTint = colors.onSurfaceSecondary,
                        title = "4. Exención de Responsabilidad",
                        content = "Las respuestas son generadas por modelos probabilísticos de lenguaje y pueden contener imprecisiones. El usuario debe evaluar la exactitud y adecuación de las respuestas antes de tomar decisiones críticas. Kofu Assistant se proporciona 'tal cual', sin garantías implícitas."
                    )

                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Kofu Assistant v2.0 • Build Edge 2026",
                            color = colors.onSurfaceMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // PIE FIJO CON BOTÓN
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    shape = KofuDesignSystem.ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.sendButtonText
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Aceptar y Continuar",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TermCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    content: String
) {
    val colors = LocalKofuColors.current

    Surface(
        color = colors.surfaceVariant,
        shape = KofuDesignSystem.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KofuDesignSystem.CardShape)
            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 13.sp
                )
            }

            Text(
                text = content,
                color = colors.onSurfaceSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
