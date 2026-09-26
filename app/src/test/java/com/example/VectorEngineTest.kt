package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.core.engine.ContourTracer
import com.example.core.engine.CurveFitter
import com.example.core.engine.LayerStackResolver
import com.example.core.engine.ParametricShapeDetector
import com.example.core.engine.QualityValidator
import com.example.core.engine.SvgWriter
import com.example.core.engine.VectorizePipeline
import com.example.core.model.ImageCategory
import com.example.core.model.ParametricShape
import com.example.core.model.PathSegment
import com.example.core.model.PointD
import com.example.core.model.QualityMode
import com.example.core.model.RectD
import com.example.core.model.SubpixelPoint
import com.example.core.model.VectorDocument
import com.example.core.model.VectorPath
import com.example.core.model.VectorRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VectorEngineTest {

    @Test
    fun testPointVectorMath() {
        val p1 = PointD(3.0, 4.0)
        assertEquals(5.0, p1.length(), 1e-6)

        val p2 = PointD(1.0, 2.0)
        val p3 = p1 - p2
        assertEquals(2.0, p3.x, 1e-6)
        assertEquals(2.0, p3.y, 1e-6)

        val normal = PointD(0.0, 1.0).normal()
        assertEquals(-1.0, normal.x, 1e-6)
        assertEquals(0.0, normal.y, 1e-6)
    }

    @Test
    fun testParametricCircleDetector() {
        val cx = 100.0
        val cy = 100.0
        val r = 50.0
        val circlePts = ArrayList<SubpixelPoint>()

        for (i in 0 until 36) {
            val theta = Math.toRadians(i * 10.0)
            val px = cx + r * cos(theta)
            val py = cy + r * sin(theta)
            circlePts.add(SubpixelPoint(PointD(px, py)))
        }

        val detected = ParametricShapeDetector.detectShape(circlePts, tolerance = 1.5, detectStars = false)
        assertNotNull("Circle should be detected for circular points", detected)
        assertTrue("Detected shape should be Circle", detected is ParametricShape.Circle)

        val circle = detected as ParametricShape.Circle
        assertEquals(cx, circle.cx, 0.5)
        assertEquals(cy, circle.cy, 0.5)
        assertEquals(r, circle.radius, 0.5)
    }

    @Test
    fun testParametricEllipseDetector() {
        val cx = 150.0
        val cy = 120.0
        val rx = 60.0
        val ry = 30.0
        val pts = ArrayList<SubpixelPoint>()
        for (i in 0 until 40) {
            val theta = (i * 2.0 * Math.PI) / 40
            val px = cx + rx * cos(theta)
            val py = cy + ry * sin(theta)
            pts.add(SubpixelPoint(PointD(px, py)))
        }

        val detected = ParametricShapeDetector.detectShape(pts, tolerance = 1.2, detectStars = false)
        assertNotNull("True ellipse must be detected", detected)
        assertTrue("Detected shape must be Ellipse", detected is ParametricShape.Ellipse)
        val el = detected as ParametricShape.Ellipse
        assertEquals(cx, el.cx, 1.0)
        assertEquals(cy, el.cy, 1.0)
        assertEquals(rx, maxOf(el.rx, el.ry), 1.5)
        assertEquals(ry, minOf(el.rx, el.ry), 1.5)
    }

    @Test
    fun testParametricPolygonAndStarDetector() {
        // Regular hexagon
        val hexPts = ArrayList<SubpixelPoint>()
        val cx = 100.0
        val cy = 100.0
        val r = 50.0
        for (i in 0 until 6) {
            val a0 = (i * 2.0 * Math.PI) / 6
            val a1 = ((i + 1) * 2.0 * Math.PI) / 6
            val p0 = PointD(cx + r * cos(a0), cy + r * sin(a0))
            val p1 = PointD(cx + r * cos(a1), cy + r * sin(a1))
            for (step in 0 until 5) {
                val t = step / 5.0
                hexPts.add(SubpixelPoint(PointD(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)))
            }
        }
        val detectedHex = ParametricShapeDetector.detectShape(hexPts, tolerance = 1.2, detectStars = false)
        assertNotNull("Hexagon must be detected", detectedHex)
        assertTrue(detectedHex is ParametricShape.RegularPolygon)
        assertEquals(6, (detectedHex as ParametricShape.RegularPolygon).sides)

        // 5-point star
        val starPts = ArrayList<SubpixelPoint>()
        val rOut = 60.0
        val rIn = 25.0
        for (i in 0 until 10) {
            val a0 = (i * 2.0 * Math.PI) / 10
            val a1 = ((i + 1) * 2.0 * Math.PI) / 10
            val r0 = if (i % 2 == 0) rOut else rIn
            val r1 = if ((i + 1) % 2 == 0) rOut else rIn
            val p0 = PointD(cx + r0 * cos(a0), cy + r0 * sin(a0))
            val p1 = PointD(cx + r1 * cos(a1), cy + r1 * sin(a1))
            for (step in 0 until 4) {
                val t = step / 4.0
                starPts.add(SubpixelPoint(PointD(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)))
            }
        }
        val detectedStar = ParametricShapeDetector.detectShape(starPts, tolerance = 1.4, detectStars = true)
        assertNotNull("Star must be detected", detectedStar)
        assertTrue(detectedStar is ParametricShape.Star)
        assertEquals(5, (detectedStar as ParametricShape.Star).points)
    }

    @Test
    fun testAdversarialIrregularBlobsMustReturnNull() {
        val cx = 100.0
        val cy = 100.0
        val r0 = 50.0

        // Blob 1: 20% harmonic radius modulation
        val blob1 = (0 until 36).map { i ->
            val theta = (i * 2.0 * Math.PI) / 36
            val r = r0 * (1.0 + 0.20 * cos(3.0 * theta))
            SubpixelPoint(PointD(cx + r * cos(theta), cy + r * sin(theta)))
        }
        org.junit.Assert.assertNull("Blob 1 (harmonic 3-lobe) must return null", ParametricShapeDetector.detectShape(blob1))

        // Blob 2: multi-frequency variance (18% + 15%)
        val blob2 = (0 until 40).map { i ->
            val theta = (i * 2.0 * Math.PI) / 40
            val r = r0 * (1.0 + 0.18 * sin(2.0 * theta) + 0.15 * cos(5.0 * theta))
            SubpixelPoint(PointD(cx + r * cos(theta), cy + r * sin(theta)))
        }
        org.junit.Assert.assertNull("Blob 2 (multi-frequency organic) must return null", ParametricShapeDetector.detectShape(blob2))

        // Blob 3: Asymmetric organic pear shape
        val blob3 = (0 until 36).map { i ->
            val theta = (i * 2.0 * Math.PI) / 36
            val r = r0 * (1.0 + 0.25 * cos(theta) - 0.20 * sin(2.0 * theta))
            SubpixelPoint(PointD(cx + r * cos(theta), cy + r * sin(theta)))
        }
        org.junit.Assert.assertNull("Blob 3 (asymmetric pear) must return null", ParametricShapeDetector.detectShape(blob3))

        // Blob 4: Noisy high-frequency perturbation
        val blob4 = (0 until 45).map { i ->
            val theta = (i * 2.0 * Math.PI) / 45
            val r = r0 * (1.0 + 0.22 * sin(7.0 * theta + 0.5))
            SubpixelPoint(PointD(cx + r * cos(theta), cy + r * sin(theta)))
        }
        org.junit.Assert.assertNull("Blob 4 (noisy organic) must return null", ParametricShapeDetector.detectShape(blob4))

        // Blob 5: Amoeba contour
        val blob5 = (0 until 40).map { i ->
            val theta = (i * 2.0 * Math.PI) / 40
            val r = r0 * (1.0 + 0.30 * cos(4.0 * theta) * sin(3.0 * theta))
            SubpixelPoint(PointD(cx + r * cos(theta), cy + r * sin(theta)))
        }
        org.junit.Assert.assertNull("Blob 5 (amoeba contour) must return null", ParametricShapeDetector.detectShape(blob5))
    }

    @Test
    fun testSvgAllowListSerialization() {
        val circlePath = VectorPath(
            segments = emptyList(),
            parametricShape = ParametricShape.Circle(50.0, 50.0, 25.0)
        )
        val region = VectorRegion(
            id = 1,
            color = android.graphics.Color.RED,
            outerPath = circlePath,
            holePaths = emptyList(),
            bounds = RectD(25.0, 25.0, 75.0, 75.0)
        )
        val doc = VectorDocument(
            width = 100,
            height = 100,
            regions = listOf(region)
        )

        val svg = SvgWriter.toSvgString(doc, precision = 2)
        assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("<circle cx=\"50.00\" cy=\"50.00\" r=\"25.00\" fill=\"#FF0000\" />"))
        // Strict security assertion: no script or foreignObject
        assertTrue(!svg.contains("<script"))
        assertTrue(!svg.contains("<foreignObject"))
        assertTrue(!svg.contains("javascript:"))
    }

    @Test
    fun testBug2_PhotographicClassificationSuppressesParametricShapes() = runBlocking {
        val bmp = createLandscapePhotoBitmap(128, 128)
        val result = VectorizePipeline.vectorize(bmp, mode = QualityMode.BALANCED)

        // 1. Assert classification correctly identified photographic / complex organic art
        assertTrue(
            "Category must be PHOTOGRAPH or COMPLEX_ART but was ${result.features.detectedCategory}",
            result.features.detectedCategory == ImageCategory.PHOTOGRAPH ||
                    result.features.detectedCategory == ImageCategory.COMPLEX_ART
        )

        // 2. Assert zero ParametricShape-typed regions in the entire output VectorDocument
        val parametricRegions = result.document.regions.filter { it.outerPath.parametricShape != null }
        assertEquals(
            "Photographic content must produce ZERO ParametricShape-typed regions, found: ${parametricRegions.size}",
            0,
            parametricRegions.size
        )
    }

    @Test
    fun testBug3_HoleAssociationStrictlyTracksParentIdentity() {
        // Two disconnected same-color blobs on the same plane:
        // Blob A at left [10..49, 10..49] has a hole at [20..39, 20..39]
        // Blob B at right [60..99, 10..49] has NO hole.
        val width = 120
        val height = 60
        val mask = BooleanArray(width * height)

        for (y in 10 until 50) {
            for (x in 10 until 50) {
                val inHole = (x in 20 until 40) && (y in 20 until 40)
                if (!inHole) {
                    mask[y * width + x] = true
                }
            }
        }

        for (y in 10 until 50) {
            for (x in 60 until 100) {
                mask[y * width + x] = true
            }
        }

        val contours = ContourTracer.traceContours(mask, width, height, minArea = 2.0)
        val outerContours = contours.filter { !it.isHole }
        val holeContours = contours.filter { it.isHole }

        assertEquals("Must trace exactly 2 outer contours", 2, outerContours.size)
        assertEquals("Must trace exactly 1 hole contour", 1, holeContours.size)

        val blobAIndex = outerContours.indexOfFirst { it.boundingBox.left < 55.0 }
        val blobBIndex = outerContours.indexOfFirst { it.boundingBox.left >= 55.0 }
        assertTrue("Blob A must be found", blobAIndex != -1)
        assertTrue("Blob B must be found", blobBIndex != -1)

        val hole = holeContours.first()
        assertEquals("Hole parentIndex must strictly point to Blob A's index", blobAIndex, hole.parentIndex)

        val holesForA = holeContours.filter { it.parentIndex == blobAIndex }
        val holesForB = holeContours.filter { it.parentIndex == blobBIndex }

        assertEquals("Blob A must have exactly 1 associated hole", 1, holesForA.size)
        assertEquals("Blob B must have zero associated holes", 0, holesForB.size)
    }

    @Test
    fun testBug4_ContinuousClosedPathPreservedUnderGapCorrection() {
        val p0 = PointD(10.0, 10.0)
        val p1 = PointD(50.0, 10.0)
        val p2 = PointD(50.0, 50.0)
        val p3 = PointD(10.0, 50.0)

        val originalPath = VectorPath(
            segments = listOf(
                PathSegment.Line(p0, p1),
                PathSegment.Line(p1, p2),
                PathSegment.Line(p2, p3),
                PathSegment.Line(p3, p0)
            )
        )

        val correctedPath = LayerStackResolver.applyGapCorrection(originalPath, offsetPx = 0.8)
        val segs = correctedPath.segments
        assertEquals("Segment count must be preserved", 4, segs.size)

        for (i in segs.indices) {
            val currSeg = segs[i]
            val nextSeg = segs[(i + 1) % segs.size]

            val currEnd = when (currSeg) {
                is PathSegment.Line -> currSeg.p1
                is PathSegment.CubicBezier -> currSeg.p3
            }
            val nextStart = when (nextSeg) {
                is PathSegment.Line -> nextSeg.p0
                is PathSegment.CubicBezier -> nextSeg.p0
            }

            assertEquals("Endpoint of segment $i must match start of segment ${(i + 1) % segs.size} x",
                currEnd.x, nextStart.x, 1e-9)
            assertEquals("Endpoint of segment $i must match start of segment ${(i + 1) % segs.size} y",
                currEnd.y, nextStart.y, 1e-9)
        }
    }

    @Test
    fun testBug4_OpaquePixelCoverageAbove99Point5PercentOnNaturePhoto() = runBlocking {
        val width = 128
        val height = 128
        val natureBmp = createLandscapePhotoBitmap(width, height)

        val result = VectorizePipeline.vectorize(natureBmp, mode = QualityMode.BALANCED)
        val reRaster = QualityValidator.rasterize(result.document, 1.0f)

        val totalPixels = width * height
        val inPixels = IntArray(totalPixels)
        val outPixels = IntArray(totalPixels)

        natureBmp.getPixels(inPixels, 0, width, 0, 0, width, height)
        reRaster.getPixels(outPixels, 0, width, 0, 0, width, height)

        var inputOpaqueCount = 0
        var outputOpaqueCount = 0

        for (i in 0 until totalPixels) {
            if (Color.alpha(inPixels[i]) >= 250) {
                inputOpaqueCount++
                if (Color.alpha(outPixels[i]) >= 250) {
                    outputOpaqueCount++
                }
            }
        }

        assertTrue("Input must have opaque pixels", inputOpaqueCount > 0)
        val coverage = outputOpaqueCount.toDouble() / inputOpaqueCount
        assertTrue(
            "Output alpha coverage must be >= 99.5% of input opaque coverage, but was ${(coverage * 100)}% ($outputOpaqueCount/$inputOpaqueCount)",
            coverage >= 0.995
        )
    }

    @Test
    fun testPhotographicNodeBudgetAndQualityFloorEnforced() = runBlocking {
        val width = 128
        val height = 128
        val natureBmp = createLandscapePhotoBitmap(width, height)

        val result = VectorizePipeline.vectorize(natureBmp, mode = QualityMode.BALANCED)

        // 1. Assert node count is strictly under the 8,000 ceiling for Balanced mode (preventing 100,000+ path nodes)
        val totalNodes = result.document.totalNodes
        assertTrue(
            "Photographic total nodes must be <= 8000, but was $totalNodes",
            totalNodes <= 8000
        )

        // 2. Assert path count is controlled and clean (not tens of thousands of fragmented islands)
        val totalPaths = result.document.totalPaths
        assertTrue(
            "Photographic total paths must be <= 1000, but was $totalPaths",
            totalPaths <= 1000
        )

        // 3. Assert SSIM does not drop below defined quality floor (0.80)
        assertTrue(
            "SSIM must meet quality floor of >= 0.80, but was ${result.qualityReport.ssim}",
            result.qualityReport.ssim >= 0.80
        )

        // 4. Assert zero black blob distortion: verify that dominant palette regions remain colorful and diverse
        val nonTransparentRegions = result.document.regions.filter { it.color != Color.TRANSPARENT }
        val distinctColorsInDoc = nonTransparentRegions.map { it.color }.distinct().size
        assertTrue(
            "Output must preserve diverse color palette without collapsing into black blob, distinct colors: $distinctColorsInDoc",
            distinctColorsInDoc >= 8
        )
    }

    @Test
    fun testTopologicalContainmentOrderingInLayerStackResolver() {
        val outerPath = VectorPath(
            segments = listOf(
                PathSegment.Line(PointD(0.0, 0.0), PointD(100.0, 0.0)),
                PathSegment.Line(PointD(100.0, 0.0), PointD(100.0, 100.0)),
                PathSegment.Line(PointD(100.0, 100.0), PointD(0.0, 100.0)),
                PathSegment.Line(PointD(0.0, 100.0), PointD(0.0, 0.0))
            )
        )
        val outerRegion = VectorRegion(
            id = 1,
            color = Color.BLUE,
            outerPath = outerPath,
            holePaths = emptyList(),
            bounds = RectD(0.0, 0.0, 100.0, 100.0)
        )

        val innerPath = VectorPath(
            segments = listOf(
                PathSegment.Line(PointD(30.0, 30.0), PointD(70.0, 30.0)),
                PathSegment.Line(PointD(70.0, 30.0), PointD(70.0, 70.0)),
                PathSegment.Line(PointD(70.0, 70.0), PointD(30.0, 70.0)),
                PathSegment.Line(PointD(30.0, 70.0), PointD(30.0, 30.0))
            )
        )
        val innerRegion = VectorRegion(
            id = 2,
            color = Color.YELLOW,
            outerPath = innerPath,
            holePaths = emptyList(),
            bounds = RectD(30.0, 30.0, 70.0, 70.0)
        )

        val resolved = LayerStackResolver.resolveLayers(listOf(innerRegion, outerRegion), gapBleedCorrectionPx = 0.0)
        assertEquals("Parent outer region must render first at zIndex 0", 1, resolved[0].id)
        assertEquals("Child inner region must render second at zIndex 1", 2, resolved[1].id)
    }

    private fun createLandscapePhotoBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Generate realistic photographic landscape with continuous spectrum and fine natural noise
        for (y in 0 until h) {
            for (x in 0 until w) {
                val noiseR = ((x * 19 + y * 37) % 31 - 15)
                val noiseG = ((x * 29 + y * 43) % 41 - 20)
                val noiseB = ((x * 31 + y * 47) % 29 - 14)

                val color = if (y < h / 2) {
                    // Sky gradient
                    val t = y.toFloat() / (h / 2)
                    val r = ((0x02 * (1 - t) + 0x7D * t) + noiseR).toInt().coerceIn(0, 255)
                    val g = ((0x84 * (1 - t) + 0xD3 * t) + noiseG).toInt().coerceIn(0, 255)
                    val b = ((0xC7 * (1 - t) + 0xFC * t) + noiseB).toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                } else {
                    // Foliage & green hillside terrain
                    val t = (y - h / 2).toFloat() / (h / 2)
                    val r = ((0x15 * (1 - t) + 0x14 * t) + noiseR).toInt().coerceIn(0, 255)
                    val g = ((0x80 * (1 - t) + 0x53 * t) + noiseG).toInt().coerceIn(0, 255)
                    val b = ((0x3D * (1 - t) + 0x2D * t) + noiseB).toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                }
                bmp.setPixel(x, y, color)
            }
        }

        // Add organic tree branches
        val canvas = Canvas(bmp)
        val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x45, 0x1A, 0x03)
            strokeWidth = 2.0f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(w * 0.1f, h.toFloat(), w * 0.3f, h * 0.55f, branchPaint)
        canvas.drawLine(w * 0.3f, h * 0.55f, w * 0.2f, h * 0.45f, branchPaint)
        canvas.drawLine(w * 0.3f, h * 0.55f, w * 0.45f, h * 0.50f, branchPaint)

        return bmp
    }
}
