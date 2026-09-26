package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.core.engine.VectorizePipeline
import com.example.core.model.QualityMode
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import java.util.Locale

@Composable
fun FineTuneDialog(
    initialSettings: VectorizePipeline.CustomSettings,
    currentMode: QualityMode,
    onDismiss: () -> Unit,
    onApply: (VectorizePipeline.CustomSettings) -> Unit
) {
    var colorLimit by remember { mutableIntStateOf(initialSettings.colorLimit ?: currentMode.colorLimit) }
    var rdpTol by remember { mutableFloatStateOf((initialSettings.rdpTolerance ?: currentMode.rdpTolerance).toFloat()) }
    var cornerDeg by remember { mutableFloatStateOf((initialSettings.cornerAngleDeg ?: 35.0).toFloat()) }
    var subpixelIters by remember { mutableIntStateOf(initialSettings.subpixelIterations ?: currentMode.subpixelIterations) }
    var enableParametric by remember { mutableStateOf(initialSettings.enableParametric ?: currentMode.enableParametric) }
    var detectStars by remember { mutableStateOf(initialSettings.detectStars ?: currentMode.detectStars) }
    var gapBleed by remember { mutableFloatStateOf((initialSettings.gapBleedCorrectionPx ?: currentMode.gapBleedCorrectionPx).toFloat()) }
    var precision by remember { mutableIntStateOf(initialSettings.coordinatePrecision ?: currentMode.coordinatePrecision) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("fine_tune_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Advanced Vector Tuning",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Override algorithmic parameters for custom fidelity balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Color Count Slider
                Text(
                    text = "Max Color Palette: $colorLimit colors",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary
                )
                Slider(
                    value = colorLimit.toFloat(),
                    onValueChange = { colorLimit = it.toInt() },
                    valueRange = 2f..64f,
                    steps = 30,
                    modifier = Modifier.testTag("slider_color_limit")
                )

                // Curve Fitting Tolerance Slider
                Text(
                    text = String.format(Locale.US, "Curve Fitting Tolerance: %.2f px", rdpTol),
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary
                )
                Slider(
                    value = rdpTol,
                    onValueChange = { rdpTol = it },
                    valueRange = 0.15f..2.5f,
                    modifier = Modifier.testTag("slider_rdp_tol")
                )

                // Corner Turning Angle
                Text(
                    text = "Corner Sharpness Threshold: ${cornerDeg.toInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary
                )
                Slider(
                    value = cornerDeg,
                    onValueChange = { cornerDeg = it },
                    valueRange = 15f..65f,
                    modifier = Modifier.testTag("slider_corner_deg")
                )

                // Subpixel Boundary Iterations
                Text(
                    text = "Sub-Pixel Sigmoid Refinement: $subpixelIters iterations",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary
                )
                Slider(
                    value = subpixelIters.toFloat(),
                    onValueChange = { subpixelIters = it.toInt() },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.testTag("slider_subpixel_iters")
                )

                // Micro-Gap Bleed Correction
                Text(
                    text = String.format(Locale.US, "Micro-Gap Bleed Correction: %.2f px", gapBleed),
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary
                )
                Slider(
                    value = gapBleed,
                    onValueChange = { gapBleed = it },
                    valueRange = 0.0f..1.5f,
                    modifier = Modifier.testTag("slider_gap_bleed")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Parametric Shape Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Detect Parametric Shapes", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Fit circles, ellipses, regular polygons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableParametric,
                        onCheckedChange = { enableParametric = it },
                        modifier = Modifier.testTag("switch_parametric")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Star Detection", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Detect 5-pt, 6-pt, and 8-pt stars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = detectStars,
                        onCheckedChange = { detectStars = it },
                        modifier = Modifier.testTag("switch_detect_stars")
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onApply(
                                VectorizePipeline.CustomSettings(
                                    colorLimit = colorLimit,
                                    rdpTolerance = rdpTol.toDouble(),
                                    cornerAngleDeg = cornerDeg.toDouble(),
                                    subpixelIterations = subpixelIters,
                                    enableParametric = enableParametric,
                                    detectStars = detectStars,
                                    gapBleedCorrectionPx = gapBleed.toDouble(),
                                    coordinatePrecision = precision
                                )
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier.testTag("btn_apply_tuning")
                    ) {
                        Text("Apply & Re-trace", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
