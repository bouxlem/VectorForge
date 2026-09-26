package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.core.engine.QualityValidator
import com.example.core.engine.SvgWriter
import com.example.core.model.VectorDocument
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    vectorDocument: VectorDocument,
    svgContent: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: SVG, 1: Android Vector, 2: High-Res PNG
    val androidVectorXml = remember(vectorDocument) {
        SvgWriter.toAndroidVectorDrawableXml(vectorDocument)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier.testTag("export_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "Export Vector Graphic",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${vectorDocument.width} × ${vectorDocument.height} px • ${vectorDocument.totalPaths} paths • ${vectorDocument.totalNodes} nodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurfaceVariant,
                contentColor = CyanPrimary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Standard SVG") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Android XML") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("High-Res PNG") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    CodePreviewBox(code = svgContent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, svgContent, "SVG Code copied")
                            },
                            modifier = Modifier.weight(1f).testTag("btn_copy_svg")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy SVG")
                        }
                        Button(
                            onClick = {
                                shareFile(context, "graphic.svg", svgContent.toByteArray(), "image/svg+xml")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            modifier = Modifier.weight(1f).testTag("btn_share_svg")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF032830))
                            Spacer(Modifier.width(6.dp))
                            Text("Share File", color = Color(0xFF032830))
                        }
                    }
                }
                1 -> {
                    CodePreviewBox(code = androidVectorXml)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            copyToClipboard(context, androidVectorXml, "Android Vector XML copied")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier.fillMaxWidth().testTag("btn_copy_android_xml")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF032830))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Android Vector Drawable XML", color = Color(0xFF032830))
                    }
                }
                2 -> {
                    Text(
                        text = "Rasterize vectors at super-resolution without quality loss:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2f to "2x HD", 4f to "4x Ultra", 8f to "8x Print").forEach { (scale, label) ->
                            OutlinedButton(
                                onClick = {
                                    val rendered = QualityValidator.rasterize(vectorDocument, scale)
                                    shareBitmap(context, "vector_${label.replace(" ", "_")}.png", rendered)
                                },
                                modifier = Modifier.weight(1f).testTag("btn_export_png_$label")
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CodePreviewBox(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF070B12), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        Text(
            text = code,
            color = Color(0xFF94A3B8),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VectorCode", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

private fun shareFile(context: Context, filename: String, data: ByteArray, mimeType: String) {
    try {
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { it.write(data) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Vector Graphic"))
    } catch (e: Exception) {
        // Fallback to text send if fileprovider fails
        val textIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, String(data))
        }
        context.startActivity(Intent.createChooser(textIntent, "Share SVG Code"))
    }
}

private fun shareBitmap(context: Context, filename: String, bitmap: Bitmap) {
    try {
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Rasterized Vector"))
    } catch (e: Exception) {
        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
