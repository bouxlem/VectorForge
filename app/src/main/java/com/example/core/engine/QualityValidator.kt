package com.example.core.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.core.model.ParametricShape
import com.example.core.model.PathSegment
import com.example.core.model.QualityReport
import com.example.core.model.VectorDocument
import com.example.core.model.VectorPath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 10: Quality Validation, Re-Rasterization & Benchmark Scoring.
 * Implements VECTOR_ALGORITHMS.md §12 & BENCHMARK.md §3.
 */
object QualityValidator {

    /**
     * Re-rasterizes a VectorDocument to a Bitmap at given scale factor.
     */
    fun rasterize(
        document: VectorDocument,
        scale: Float = 1.0f
    ): Bitmap {
        val targetWidth = (document.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (document.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        for (region in document.regions) {
            if (region.color == Color.TRANSPARENT) continue
            paint.color = region.color

            val path = Path().apply {
                fillType = if (region.holePaths.isNotEmpty()) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            }

            appendPathToAndroidPath(region.outerPath, path)
            for (hole in region.holePaths) {
                appendPathToAndroidPath(hole, path)
            }

            canvas.drawPath(path, paint)
        }

        // In headless JVM / Robolectric test environments where Canvas.drawPath is a no-op,
        // populate bitmap pixels via deterministic scanline rasterization
        val samplePixel = bitmap.getPixel(targetWidth / 2, targetHeight / 2)
        if (samplePixel == 0 && document.regions.isNotEmpty()) {
            rasterizeSoftwareFallback(document, bitmap, scale)
        }

        return bitmap
    }

    private fun rasterizeSoftwareFallback(
        document: VectorDocument,
        bitmap: Bitmap,
        scale: Float
    ) {
        val width = bitmap.width
        val height = bitmap.height

        for (region in document.regions) {
            if (region.color == Color.TRANSPARENT) continue
            val outerPts = extractPolygonPoints(region.outerPath, scale)
            if (outerPts.size < 3) continue

            fillPolygonScanline(bitmap, outerPts, region.color, width, height)

            for (hole in region.holePaths) {
                val holePts = extractPolygonPoints(hole, scale)
                if (holePts.size >= 3) {
                    fillPolygonScanline(bitmap, holePts, Color.TRANSPARENT, width, height)
                }
            }
        }
    }

    private fun extractPolygonPoints(vPath: VectorPath, scale: Float): List<com.example.core.model.PointD> {
        val shape = vPath.parametricShape
        if (shape != null) {
            when (shape) {
                is ParametricShape.Circle -> {
                    return (0 until 32).map { i ->
                        val a = (i * 2.0 * Math.PI) / 32
                        com.example.core.model.PointD((shape.cx + shape.radius * cos(a)) * scale, (shape.cy + shape.radius * sin(a)) * scale)
                    }
                }
                is ParametricShape.Ellipse -> {
                    return (0 until 32).map { i ->
                        val a = (i * 2.0 * Math.PI) / 32
                        com.example.core.model.PointD((shape.cx + shape.rx * cos(a)) * scale, (shape.cy + shape.ry * sin(a)) * scale)
                    }
                }
                is ParametricShape.RegularPolygon -> {
                    val angleStep = (2.0 * Math.PI) / shape.sides
                    val startRad = Math.toRadians(shape.rotationDeg)
                    return (0 until shape.sides).map { i ->
                        val a = startRad + i * angleStep
                        com.example.core.model.PointD((shape.cx + shape.radius * cos(a)) * scale, (shape.cy + shape.radius * sin(a)) * scale)
                    }
                }
                is ParametricShape.Star -> {
                    val totalRays = shape.points * 2
                    val angleStep = (2.0 * Math.PI) / totalRays
                    val startRad = Math.toRadians(shape.rotationDeg)
                    return (0 until totalRays).map { i ->
                        val a = startRad + i * angleStep
                        val r = if (i % 2 == 0) shape.outerRadius else shape.innerRadius
                        com.example.core.model.PointD((shape.cx + r * cos(a)) * scale, (shape.cy + r * sin(a)) * scale)
                    }
                }
            }
        }

        val pts = ArrayList<com.example.core.model.PointD>()
        for (seg in vPath.segments) {
            when (seg) {
                is PathSegment.Line -> {
                    pts.add(com.example.core.model.PointD(seg.p0.x * scale, seg.p0.y * scale))
                }
                is PathSegment.CubicBezier -> {
                    for (step in 0 until 4) {
                        val t = step / 4.0
                        val invT = 1.0 - t
                        val x = invT * invT * invT * seg.p0.x +
                                3 * invT * invT * t * seg.p1.x +
                                3 * invT * t * t * seg.p2.x +
                                t * t * t * seg.p3.x
                        val y = invT * invT * invT * seg.p0.y +
                                3 * invT * invT * t * seg.p1.y +
                                3 * invT * t * t * seg.p2.y +
                                t * t * t * seg.p3.y
                        pts.add(com.example.core.model.PointD(x * scale, y * scale))
                    }
                }
            }
        }
        return pts
    }

    private fun fillPolygonScanline(
        bitmap: Bitmap,
        pts: List<com.example.core.model.PointD>,
        color: Int,
        width: Int,
        height: Int
    ) {
        val n = pts.size
        var minY = height - 1
        var maxY = 0
        for (p in pts) {
            val py = p.y.toInt()
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        minY = minY.coerceIn(0, height - 1)
        maxY = maxY.coerceIn(0, height - 1)
        if (minY > maxY) return

        val intersections = ArrayList<Double>(8)
        for (y in minY..maxY) {
            intersections.clear()
            val scanY = y + 0.5
            for (i in 0 until n) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % n]
                if ((p1.y <= scanY && p2.y > scanY) || (p2.y <= scanY && p1.y > scanY)) {
                    val t = (scanY - p1.y) / (p2.y - p1.y)
                    intersections.add(p1.x + t * (p2.x - p1.x))
                }
            }
            if (intersections.size < 2) continue
            intersections.sort()

            for (k in 0 until intersections.size - 1 step 2) {
                val xStart = intersections[k].toInt().coerceIn(0, width - 1)
                val xEnd = intersections[k + 1].toInt().coerceIn(0, width - 1)
                for (x in xStart..xEnd) {
                    bitmap.setPixel(x, y, color)
                }
            }
        }
    }

    private fun appendPathToAndroidPath(vPath: VectorPath, androidPath: Path) {
        val shape = vPath.parametricShape
        if (shape != null) {
            when (shape) {
                is ParametricShape.Circle -> {
                    androidPath.addCircle(
                        shape.cx.toFloat(),
                        shape.cy.toFloat(),
                        shape.radius.toFloat(),
                        Path.Direction.CW
                    )
                    return
                }
                is ParametricShape.Ellipse -> {
                    androidPath.addOval(
                        (shape.cx - shape.rx).toFloat(),
                        (shape.cy - shape.ry).toFloat(),
                        (shape.cx + shape.rx).toFloat(),
                        (shape.cy + shape.ry).toFloat(),
                        Path.Direction.CW
                    )
                    return
                }
                is ParametricShape.RegularPolygon -> {
                    val angleStep = (2.0 * Math.PI) / shape.sides
                    val startRad = Math.toRadians(shape.rotationDeg)
                    for (i in 0 until shape.sides) {
                        val a = startRad + i * angleStep
                        val px = (shape.cx + shape.radius * cos(a)).toFloat()
                        val py = (shape.cy + shape.radius * sin(a)).toFloat()
                        if (i == 0) androidPath.moveTo(px, py) else androidPath.lineTo(px, py)
                    }
                    androidPath.close()
                    return
                }
                is ParametricShape.Star -> {
                    val totalRays = shape.points * 2
                    val angleStep = (2.0 * Math.PI) / totalRays
                    val startRad = Math.toRadians(shape.rotationDeg)
                    for (i in 0 until totalRays) {
                        val a = startRad + i * angleStep
                        val r = if (i % 2 == 0) shape.outerRadius else shape.innerRadius
                        val px = (shape.cx + r * cos(a)).toFloat()
                        val py = (shape.cy + r * sin(a)).toFloat()
                        if (i == 0) androidPath.moveTo(px, py) else androidPath.lineTo(px, py)
                    }
                    androidPath.close()
                    return
                }
            }
        }

        if (vPath.segments.isEmpty()) return
        val first = vPath.segments.first()
        val startPt = when (first) {
            is PathSegment.Line -> first.p0
            is PathSegment.CubicBezier -> first.p0
        }
        androidPath.moveTo(startPt.x.toFloat(), startPt.y.toFloat())

        for (seg in vPath.segments) {
            when (seg) {
                is PathSegment.Line -> {
                    androidPath.lineTo(seg.p1.x.toFloat(), seg.p1.y.toFloat())
                }
                is PathSegment.CubicBezier -> {
                    androidPath.cubicTo(
                        seg.p1.x.toFloat(), seg.p1.y.toFloat(),
                        seg.p2.x.toFloat(), seg.p2.y.toFloat(),
                        seg.p3.x.toFloat(), seg.p3.y.toFloat()
                    )
                }
            }
        }
        androidPath.close()
    }

    /**
     * Evaluates fidelity between the original raster bitmap and the vector document.
     */
    fun evaluateQuality(
        original: Bitmap,
        document: VectorDocument,
        svgString: String,
        executionTimeMs: Long,
        timings: Map<String, Long> = emptyMap()
    ): QualityReport {
        val reRaster = rasterize(document, 1.0f)
        val w = minOf(original.width, reRaster.width)
        val h = minOf(original.height, reRaster.height)

        val origPixels = IntArray(w * h)
        val vectorPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        reRaster.getPixels(vectorPixels, 0, w, 0, 0, w, h)

        var mseSum = 0.0
        var edgeInter = 0
        var edgeUnion = 0

        // Luminance arrays for SSIM and Edge detection
        val lumOrig = DoubleArray(w * h)
        val lumVec = DoubleArray(w * h)

        for (i in 0 until (w * h)) {
            val c1 = origPixels[i]
            val c2 = vectorPixels[i]

            val r1 = Color.red(c1)
            val g1 = Color.green(c1)
            val b1 = Color.blue(c1)
            val a1 = Color.alpha(c1)

            val r2 = Color.red(c2)
            val g2 = Color.green(c2)
            val b2 = Color.blue(c2)
            val a2 = Color.alpha(c2)

            val dr = (r1 - r2)
            val dg = (g1 - g2)
            val db = (b1 - b2)
            val da = (a1 - a2)
            mseSum += (dr * dr + dg * dg + db * db + da * da) / 4.0

            lumOrig[i] = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
            lumVec[i] = 0.299 * r2 + 0.587 * g2 + 0.114 * b2
        }

        val mse = (mseSum / (w * h)).coerceAtLeast(1e-6)
        val psnr = (10.0 * log10((255.0 * 255.0) / mse)).coerceIn(10.0, 60.0)

        // Compute Edge IoU using Sobel gradient threshold
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx1 = lumOrig[idx + 1] - lumOrig[idx - 1]
                val gy1 = lumOrig[idx + w] - lumOrig[idx - w]
                val isEdgeOrig = (abs(gx1) + abs(gy1)) > 30.0

                val gx2 = lumVec[idx + 1] - lumVec[idx - 1]
                val gy2 = lumVec[idx + w] - lumVec[idx - w]
                val isEdgeVec = (abs(gx2) + abs(gy2)) > 30.0

                if (isEdgeOrig || isEdgeVec) {
                    edgeUnion++
                    if (isEdgeOrig && isEdgeVec) {
                        edgeInter++
                    }
                }
            }
        }

        val edgeIoU = if (edgeUnion > 0) (edgeInter.toDouble() / edgeUnion).coerceIn(0.0, 1.0) else 1.0

        // SSIM calculation (Simplified multi-region SSIM)
        val ssim = computeSsim(lumOrig, lumVec, w, h)

        return QualityReport(
            ssim = ssim,
            edgeIoU = edgeIoU,
            psnr = psnr,
            pathCount = document.totalPaths,
            nodeCount = document.totalNodes,
            fileSizeBytes = svgString.toByteArray().size.toLong(),
            executionTimeMs = executionTimeMs,
            stageTimings = timings
        )
    }

    private fun computeSsim(img1: DoubleArray, img2: DoubleArray, w: Int, h: Int): Double {
        val total = w * h
        var mean1 = 0.0
        var mean2 = 0.0
        for (i in 0 until total) {
            mean1 += img1[i]
            mean2 += img2[i]
        }
        mean1 /= total
        mean2 /= total

        var var1 = 0.0
        var var2 = 0.0
        var covar = 0.0
        for (i in 0 until total) {
            val d1 = img1[i] - mean1
            val d2 = img2[i] - mean2
            var1 += d1 * d1
            var2 += d2 * d2
            covar += d1 * d2
        }
        var1 /= total
        var2 /= total
        covar /= total

        val c1 = (0.01 * 255.0) * (0.01 * 255.0)
        val c2 = (0.03 * 255.0) * (0.03 * 255.0)

        val ssim = ((2.0 * mean1 * mean2 + c1) * (2.0 * covar + c2)) /
                ((mean1 * mean1 + mean2 * mean2 + c1) * (var1 + var2 + c2))

        return ssim.coerceIn(0.0, 1.0)
    }
}
