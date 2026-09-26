package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.QualityMode
import com.example.data.fixtures.BenchmarkFixtures
import com.example.ui.StudioViewModel
import com.example.ui.VectorizeUiState
import com.example.ui.ViewInspectionMode
import com.example.ui.components.ExportSheet
import com.example.ui.components.FineTuneDialog
import com.example.ui.components.VectorCanvas
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.VioletSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentBmp by viewModel.currentBitmap.collectAsState()
    val imageTitle by viewModel.currentImageTitle.collectAsState()
    val qualityMode by viewModel.qualityMode.collectAsState()
    val customSettings by viewModel.customSettings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val inspectionMode by viewModel.inspectionMode.collectAsState()
    val splitRatio by viewModel.splitRatio.collectAsState()
    val showFineTune by viewModel.showFineTuneDialog.collectAsState()

    var showExportSheet by remember { mutableStateOf(false) }
    var showFixtureMenu by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadFromUri(it) }
    }

    val successResult = (uiState as? VectorizeUiState.Success)?.result

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Studio Header Bar
            Surface(
                color = DarkSurface,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VectorForge Studio",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = CyanPrimary
                        )
                        Text(
                            text = imageTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sample Fixtures Dropdown
                        Box {
                            IconButton(
                                onClick = { showFixtureMenu = true },
                                modifier = Modifier.testTag("btn_sample_fixtures")
                            ) {
                                Icon(
                                    Icons.Default.Collections,
                                    contentDescription = "Sample Fixtures",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showFixtureMenu,
                                onDismissRequest = { showFixtureMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                Text(
                                    text = "Preset Benchmark Graphics",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                BenchmarkFixtures.allFixtures.forEach { fixture ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(fixture.name, fontWeight = FontWeight.Medium)
                                                Text(fixture.categoryName, fontSize = 11.sp, color = Color.Gray)
                                            }
                                        },
                                        onClick = {
                                            viewModel.loadFixture(fixture)
                                            showFixtureMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Pick Custom Image
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("btn_pick_image")
                        ) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = "Pick Custom Image",
                                tint = CyanPrimary
                            )
                        }

                        // Fine-Tune Settings
                        IconButton(
                            onClick = { viewModel.setShowFineTuneDialog(true) },
                            modifier = Modifier.testTag("btn_open_fine_tune")
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Fine-Tune Parameters",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 2. Quality Mode Selector Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant.copy(alpha = 0.6f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QualityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = qualityMode == mode,
                        onClick = { viewModel.setQualityMode(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = CyanPrimary,
                            containerColor = Color.Transparent,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = qualityMode == mode,
                            borderColor = if (qualityMode == mode) CyanPrimary else DarkBorder
                        ),
                        modifier = Modifier.testTag("chip_mode_${mode.name}")
                    )
                }
            }

            // 3. Main Vector Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                VectorCanvas(
                    originalBitmap = currentBmp,
                    vectorBitmap = successResult?.reRasterizedBitmap,
                    vectorDocument = successResult?.document,
                    inspectionMode = inspectionMode,
                    splitRatio = splitRatio,
                    onSplitRatioChange = { viewModel.setSplitRatio(it) }
                )

                // Processing Stage Progress Overlay
                val processing = uiState as? VectorizeUiState.Processing
                androidx.compose.animation.AnimatedVisibility(
                    visible = processing != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    processing?.let { proc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(0.9f)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = proc.stage,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = CyanPrimary
                                    )
                                    Text(
                                        text = "${(proc.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { proc.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = CyanPrimary,
                                    trackColor = DarkBorder
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = proc.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 4. Inspection Mode Selector Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ViewInspectionMode.entries.forEach { mode ->
                    val isSelected = inspectionMode == mode
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) CyanPrimary.copy(alpha = 0.15f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.setInspectionMode(mode) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("mode_${mode.name}")
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 5. Quality Metrics & Action Footer
            successResult?.let { result ->
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // SSIM Metric Badge
                            MetricPill(
                                label = "SSIM",
                                value = String.format(Locale.US, "%.1f%%", result.qualityReport.ssim * 100),
                                color = EmeraldSuccess
                            )
                            // Edge IoU
                            MetricPill(
                                label = "Edge-IoU",
                                value = String.format(Locale.US, "%.1f%%", result.qualityReport.edgeIoU * 100),
                                color = CyanPrimary
                            )
                            // Nodes & Paths
                            MetricPill(
                                label = "Nodes",
                                value = "${result.qualityReport.nodeCount} (${result.qualityReport.pathCount}p)",
                                color = VioletSecondary
                            )
                            // Latency
                            MetricPill(
                                label = "Time",
                                value = "${result.qualityReport.executionTimeMs}ms",
                                color = AmberWarning
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveCurrentProject()
                                    Toast.makeText(context, "Saved to Projects!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_save_project")
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save")
                            }

                            Button(
                                onClick = { showExportSheet = true },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("btn_export_open")
                            ) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = null,
                                    tint = Color(0xFF032830),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Export SVG / PNG", color = Color(0xFF032830), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Fine-Tune Dialog
    if (showFineTune) {
        FineTuneDialog(
            initialSettings = customSettings,
            currentMode = qualityMode,
            onDismiss = { viewModel.setShowFineTuneDialog(false) },
            onApply = { newSettings -> viewModel.updateCustomSettings(newSettings) }
        )
    }

    // Export Bottom Sheet
    if (showExportSheet && successResult != null) {
        ExportSheet(
            vectorDocument = successResult.document,
            svgContent = successResult.svgContent,
            onDismiss = { showExportSheet = false }
        )
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
