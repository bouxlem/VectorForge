package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.core.model.ParametricShape
import com.example.core.model.PathSegment
import com.example.core.model.VectorDocument
import com.example.ui.ViewInspectionMode
import com.example.ui.theme.CheckerboardDark
import com.example.ui.theme.CheckerboardLight
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.VioletSecondary
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun VectorCanvas(
    originalBitmap: Bitmap?,
    vectorBitmap: Bitmap?,
    vectorDocument: VectorDocument?,
    inspectionMode: ViewInspectionMode,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("vector_canvas_container")
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 16.0f)
                    panOffset += pan
                }
            }
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        val docW = originalBitmap?.width?.toFloat() ?: 512f
        val docH = originalBitmap?.height?.toFloat() ?: 512f

        // Fit image inside container initially
        val baseFitScale = minOf(containerWidth / docW, containerHeight / docH) * 0.9f
        val currentScale = baseFitScale * scale

        val contentWidth = docW * currentScale
        val contentHeight = docH * currentScale

        val centerOrigin = Offset(
            (containerWidth - contentWidth) / 2f + panOffset.x,
            (containerHeight - contentHeight) / 2f + panOffset.y
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Draw Transparency Checkerboard Pattern
            val checkSize = 16.dp.toPx()
            val cols = (size.width / checkSize).toInt() + 1
            val rows = (size.height / checkSize).toInt() + 1

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val color = if ((r + c) % 2 == 0) CheckerboardLight else CheckerboardDark
                    drawRect(
                        color = color,
                        topLeft = Offset(c * checkSize, r * checkSize),
                        size = Size(checkSize, checkSize)
                    )
                }
            }

            // Draw bounding shadow box around image
            drawRect(
                color = Color(0x66000000),
                topLeft = centerOrigin,
                size = Size(contentWidth, contentHeight)
            )

            val origImg = originalBitmap?.asImageBitmap()
            val vecImg = vectorBitmap?.asImageBitmap()

            when (inspectionMode) {
                ViewInspectionMode.RASTER_ONLY -> {
                    origImg?.let {
                        drawImage(
                            image = it,
                            dstOffset = IntOffset(centerOrigin.x.roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(contentWidth.roundToInt(), contentHeight.roundToInt())
                        )
                    }
                }
                ViewInspectionMode.VECTOR_ONLY -> {
                    vecImg?.let {
                        drawImage(
                            image = it,
                            dstOffset = IntOffset(centerOrigin.x.roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(contentWidth.roundToInt(), contentHeight.roundToInt())
                        )
                    }
                }
                ViewInspectionMode.SIDE_BY_SIDE -> {
                    // Draw Raster on left half, Vector on right half
                    val halfW = contentWidth / 2f
                    origImg?.let {
                        drawImage(
                            image = it,
                            srcSize = IntSize(it.width / 2, it.height),
                            dstOffset = IntOffset(centerOrigin.x.roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(halfW.roundToInt(), contentHeight.roundToInt())
                        )
                    }
                    vecImg?.let {
                        drawImage(
                            image = it,
                            srcOffset = IntOffset(it.width / 2, 0),
                            srcSize = IntSize(it.width / 2, it.height),
                            dstOffset = IntOffset((centerOrigin.x + halfW).roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(halfW.roundToInt(), contentHeight.roundToInt())
                        )
                    }
                    // Divider line
                    drawLine(
                        color = CyanPrimary,
                        start = Offset(centerOrigin.x + halfW, centerOrigin.y),
                        end = Offset(centerOrigin.x + halfW, centerOrigin.y + contentHeight),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                ViewInspectionMode.SPLIT_SLIDER -> {
                    val splitX = contentWidth * splitRatio
                    // Draw original raster on left side of split
                    origImg?.let {
                        val srcSplit = (it.width * splitRatio).toInt().coerceIn(1, it.width)
                        drawImage(
                            image = it,
                            srcSize = IntSize(srcSplit, it.height),
                            dstOffset = IntOffset(centerOrigin.x.roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(splitX.roundToInt(), contentHeight.roundToInt())
                        )
                    }
                    // Draw vector graphic on right side of split
                    vecImg?.let {
                        val srcStart = (it.width * splitRatio).toInt().coerceIn(0, it.width - 1)
                        val srcRemaining = it.width - srcStart
                        val dstW = contentWidth - splitX
                        if (srcRemaining > 0 && dstW > 0) {
                            drawImage(
                                image = it,
                                srcOffset = IntOffset(srcStart, 0),
                                srcSize = IntSize(srcRemaining, it.height),
                                dstOffset = IntOffset((centerOrigin.x + splitX).roundToInt(), centerOrigin.y.roundToInt()),
                                dstSize = IntSize(dstW.roundToInt(), contentHeight.roundToInt())
                            )
                        }
                    }

                    // Split Line Divider
                    val lineX = centerOrigin.x + splitX
                    drawLine(
                        color = CyanPrimary,
                        start = Offset(lineX, centerOrigin.y - 12.dp.toPx()),
                        end = Offset(lineX, centerOrigin.y + contentHeight + 12.dp.toPx()),
                        strokeWidth = 3.dp.toPx()
                    )
                }
                ViewInspectionMode.WIREFRAME_NODES -> {
                    // Underlay faint original raster
                    origImg?.let {
                        drawImage(
                            image = it,
                            dstOffset = IntOffset(centerOrigin.x.roundToInt(), centerOrigin.y.roundToInt()),
                            dstSize = IntSize(contentWidth.roundToInt(), contentHeight.roundToInt()),
                            alpha = 0.25f
                        )
                    }

                    // Draw wireframe vector curves & anchor nodes
                    vectorDocument?.let { doc ->
                        val strokePaint = Stroke(width = 1.5.dp.toPx())
                        for (region in doc.regions) {
                            drawRegionWireframe(
                                region = region,
                                origin = centerOrigin,
                                scale = currentScale,
                                stroke = strokePaint
                            )
                        }
                    }
                }
            }
        }

        // Draggable Split Slider Handle in SPLIT_SLIDER mode
        if (inspectionMode == ViewInspectionMode.SPLIT_SLIDER) {
            val handleOffsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
            val handleX = centerOrigin.x + contentWidth * splitRatio - handleOffsetPx
            val handleY = centerOrigin.y + (contentHeight / 2f) - handleOffsetPx

            Box(
                modifier = Modifier
                    .offset { IntOffset(handleX.roundToInt(), handleY.roundToInt()) }
                    .size(36.dp)
                    .background(CyanPrimary, CircleShape)
                    .testTag("split_slider_handle")
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, _, _ ->
                            if (contentWidth > 0) {
                                val deltaRatio = pan.x / contentWidth
                                onSplitRatioChange(splitRatio + deltaRatio)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "Split Slider Handle",
                    tint = Color(0xFF032830),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRegionWireframe(
    region: com.example.core.model.VectorRegion,
    origin: Offset,
    scale: Float,
    stroke: Stroke
) {
    val path = Path()
    val outer = region.outerPath

    val shape = outer.parametricShape
    if (shape != null) {
        when (shape) {
            is ParametricShape.Circle -> {
                drawCircle(
                    color = EmeraldSuccess,
                    radius = (shape.radius * scale).toFloat(),
                    center = Offset((origin.x + shape.cx * scale).toFloat(), (origin.y + shape.cy * scale).toFloat()),
                    style = stroke
                )
                // Draw 4 cardinal anchor nodes
                for (a in listOf(0.0, 90.0, 180.0, 270.0)) {
                    val rad = Math.toRadians(a)
                    drawCircle(
                        color = Color.White,
                        radius = 3.5.dp.toPx(),
                        center = Offset(
                            (origin.x + (shape.cx + shape.radius * cos(rad)) * scale).toFloat(),
                            (origin.y + (shape.cy + shape.radius * sin(rad)) * scale).toFloat()
                        )
                    )
                }
                return
            }
            is ParametricShape.RegularPolygon -> {
                val step = (2.0 * Math.PI) / shape.sides
                val rot = Math.toRadians(shape.rotationDeg)
                val polyPath = Path()
                for (i in 0 until shape.sides) {
                    val a = rot + i * step
                    val px = (origin.x + (shape.cx + shape.radius * cos(a)) * scale).toFloat()
                    val py = (origin.y + (shape.cy + shape.radius * sin(a)) * scale).toFloat()
                    if (i == 0) polyPath.moveTo(px, py) else polyPath.lineTo(px, py)
                    drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(px, py))
                }
                polyPath.close()
                drawPath(polyPath, color = EmeraldSuccess, style = stroke)
                return
            }
            else -> {}
        }
    }

    if (outer.segments.isEmpty()) return
    val first = outer.segments.first()
    val startPt = when (first) {
        is PathSegment.Line -> first.p0
        is PathSegment.CubicBezier -> first.p0
    }
    path.moveTo(
        (origin.x + startPt.x * scale).toFloat(),
        (origin.y + startPt.y * scale).toFloat()
    )

    for (seg in outer.segments) {
        when (seg) {
            is PathSegment.Line -> {
                val px = (origin.x + seg.p1.x * scale).toFloat()
                val py = (origin.y + seg.p1.y * scale).toFloat()
                path.lineTo(px, py)
                // Anchor node
                drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(px, py))
            }
            is PathSegment.CubicBezier -> {
                val p1x = (origin.x + seg.p1.x * scale).toFloat()
                val p1y = (origin.y + seg.p1.y * scale).toFloat()
                val p2x = (origin.x + seg.p2.x * scale).toFloat()
                val p2y = (origin.y + seg.p2.y * scale).toFloat()
                val p3x = (origin.x + seg.p3.x * scale).toFloat()
                val p3y = (origin.y + seg.p3.y * scale).toFloat()

                path.cubicTo(p1x, p1y, p2x, p2y, p3x, p3y)

                // Control handle lines (cyan)
                val p0x = (origin.x + seg.p0.x * scale).toFloat()
                val p0y = (origin.y + seg.p0.y * scale).toFloat()
                drawLine(color = CyanPrimary.copy(alpha = 0.6f), start = Offset(p0x, p0y), end = Offset(p1x, p1y))
                drawLine(color = CyanPrimary.copy(alpha = 0.6f), start = Offset(p3x, p3y), end = Offset(p2x, p2y))

                // Control points (cyan squares/circles)
                drawCircle(color = CyanPrimary, radius = 2.5.dp.toPx(), center = Offset(p1x, p1y))
                drawCircle(color = CyanPrimary, radius = 2.5.dp.toPx(), center = Offset(p2x, p2y))
                // Anchor endpoint (white)
                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(p3x, p3y))
            }
        }
    }
    path.close()
    drawPath(path = path, color = VioletSecondary, style = stroke)
}
