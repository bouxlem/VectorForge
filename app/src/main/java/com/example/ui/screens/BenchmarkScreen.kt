package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.fixtures.BenchmarkFixture
import com.example.data.fixtures.BenchmarkFixtures
import com.example.ui.BenchmarkItemResult
import com.example.ui.StudioViewModel
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import java.util.Locale

@Composable
fun BenchmarkScreen(
    viewModel: StudioViewModel,
    onNavigateToStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val benchmarkResults by viewModel.benchmarkResults.collectAsState()
    val isRunning by viewModel.isBenchmarkRunning.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp)
            .testTag("benchmark_screen")
    ) {
        // Benchmark Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Benchmark Suite",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Automated SSIM & Edge-IoU quality verification per BENCHMARK.md",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run Benchmark Button
        Button(
            onClick = { viewModel.runBenchmarkSuite() },
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_run_benchmark")
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    color = Color(0xFF032830),
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Benchmarking In Progress...", color = Color(0xFF032830), fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF032830))
                Spacer(Modifier.width(6.dp))
                Text("Run Benchmark Suite (8 Fixtures)", color = Color(0xFF032830), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Summary Card if run completed
        if (benchmarkResults.isNotEmpty()) {
            val avgSsim = benchmarkResults.map { it.ssim }.average()
            val avgEdge = benchmarkResults.map { it.edgeIoU }.average()
            val passedCount = benchmarkResults.count { it.passed }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    SummaryStat("Passed", "$passedCount / ${benchmarkResults.size}", EmeraldSuccess)
                    SummaryStat("Avg SSIM", String.format(Locale.US, "%.1f%%", avgSsim * 100), CyanPrimary)
                    SummaryStat("Avg Edge-IoU", String.format(Locale.US, "%.1f%%", avgEdge * 100), EmeraldSuccess)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Fixtures List
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(BenchmarkFixtures.allFixtures) { fixture ->
                val result = benchmarkResults.find { it.fixture.id == fixture.id }
                FixtureCard(
                    fixture = fixture,
                    result = result,
                    onTestInStudio = {
                        viewModel.loadFixture(fixture)
                        onNavigateToStudio()
                    }
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FixtureCard(
    fixture: BenchmarkFixture,
    result: BenchmarkItemResult?,
    onTestInStudio: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#${fixture.id} ${fixture.name}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = fixture.categoryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanPrimary
                    )
                }

                if (result != null) {
                    val pass = result.passed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = if (pass) EmeraldSuccess.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (pass) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (pass) EmeraldSuccess else AmberWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (pass) "PASSED" else "MARGINAL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pass) EmeraldSuccess else AmberWarning
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = fixture.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Target Criteria: ${fixture.expectedOutcome}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )

            if (result != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.US, "SSIM: %.1f%% (tgt %.0f%%)", result.ssim * 100, fixture.targetSsim * 100),
                        fontSize = 12.sp,
                        color = if (result.ssim >= fixture.targetSsim) EmeraldSuccess else AmberWarning
                    )
                    Text(
                        text = String.format(Locale.US, "Edge-IoU: %.1f%% (tgt %.0f%%)", result.edgeIoU * 100, fixture.targetEdgeIou * 100),
                        fontSize = 12.sp,
                        color = if (result.edgeIoU >= fixture.targetEdgeIou) EmeraldSuccess else AmberWarning
                    )
                    Text(
                        text = "${result.nodeCount} nodes • ${result.timeMs}ms",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onTestInStudio,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open & Inspect in Studio", fontSize = 12.sp)
            }
        }
    }
}
