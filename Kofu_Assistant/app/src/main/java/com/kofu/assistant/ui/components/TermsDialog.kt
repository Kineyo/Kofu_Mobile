package com.kofu.assistant.ui.components

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
import androidx.compose.ui.text.font.FontFamily
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
                    .padding(14.dp)
            ) {
                // CABECERA FORMAL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "TÉRMINOS Y PRIVACIDAD",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Kofu Assistant • Licencia Gemma",
                            color = colors.onSurfaceSecondary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = colors.onSurfaceSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                // CONTENIDO DESPLAZABLE FORMAL
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FormalTermCard(
                        title = "1. PRIVACIDAD Y SOBERANÍA LOCAL",
                        content = "Kofu procesa tus conversaciones exclusivamente de manera local en el dispositivo o a través de tu servidor Ollama en red privada. Ningún mensaje, prompt, metadato o dato personal se transmite a la nube ni a servidores externos de terceros. Los datos permanecen estrictamente bajo el control del usuario."
                    )

                    FormalTermCard(
                        title = "2. MODELO BASE Y LICENCIA GEMMA",
                        content = "Kofu está optimizado sobre la familia de modelos abiertos Gemma de Google DeepMind. El uso de los pesos e inferencias está sujeto a los Términos de Uso de Gemma (Gemma Terms of Use) y la Licencia Apache 2.0 en componentes de software libre aplicables. Queda garantizado el derecho a uso personal, educativo y comercial conforme a dichos términos."
                    )

                    FormalTermCard(
                        title = "3. POLÍTICA DE USO PROHIBIDO",
                        content = "De conformidad con los lineamientos de Google DeepMind y las directrices de IA responsable, está estrictamente prohibido utilizar Kofu para:\n" +
                                "• Generar contenido ilícito, violento, acoso o incitación al odio.\n" +
                                "• Desarrollar código malicioso, exploits o ataques contra infraestructura ajena.\n" +
                                "• Difundir desinformación deliberada o asesoramiento médico/financiero crítico sin validación profesional.\n" +
                                "• Infringir derechos de privacidad o propiedad intelectual de terceros."
                    )

                    FormalTermCard(
                        title = "4. EXENCIÓN DE RESPONSABILIDAD",
                        content = "Las respuestas son generadas por modelos probabilísticos de lenguaje y pueden contener imprecisiones. El usuario debe evaluar la exactitud y adecuación de las respuestas antes de tomar decisiones técnicas o críticas. Kofu Assistant se proporciona 'tal cual', sin garantías implícitas."
                    )

                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Kofu Assistant v2.0 • Build Edge 2026",
                            color = colors.onSurfaceMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // PIE FIJO
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

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
                        text = "Aceptar",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FormalTermCard(
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
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )

            Text(
                text = content,
                color = colors.onSurfaceSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}
