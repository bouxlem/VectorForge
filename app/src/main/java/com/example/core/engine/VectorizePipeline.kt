package com.example.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.model.Contour
import com.example.core.model.ImageFeatures
import com.example.core.model.PointD
import com.example.core.model.QualityMode
import com.example.core.model.QualityReport
import com.example.core.model.RectD
import com.example.core.model.VectorDocument
import com.example.core.model.VectorPath
import com.example.core.model.VectorRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Master Vectorization Pipeline Orchestrator.
 * Implements ARCHITECTURE.md end-to-end.
 */
object VectorizePipeline {

    data class PipelineResult(
        val document: VectorDocument,
        val svgContent: String,
        val qualityReport: QualityReport,
        val features: ImageFeatures,
        val reRasterizedBitmap: Bitmap,
        val processedOriginalBitmap: Bitmap
    )

    data class CustomSettings(
        val colorLimit: Int? = null,
        val rdpTolerance: Double? = null,
        val cornerAngleDeg: Double? = null,
        val subpixelIterations: Int? = null,
        val enableParametric: Boolean? = null,
        val detectStars: Boolean? = null,
        val gapBleedCorrectionPx: Double? = null,
        val coordinatePrecision: Int? = null
    )

    /**
     * Runs full raster-to-vector pipeline with stage progress notifications.
     */
    suspend fun vectorize(
        inputBitmap: Bitmap,
        mode: QualityMode = QualityMode.BALANCED,
        customSettings: CustomSettings = CustomSettings(),
        onProgress: (stage: String, progress: Float, details: String) -> Unit = { _, _, _ -> }
    ): PipelineResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val timings = mutableMapOf<String, Long>()

        // 1. Decode & Preprocess / Resize
        onProgress("Preprocessing", 0.05f, "Normalizing dimensions & color space...")
        val t0 = System.currentTimeMillis()
        val maxDim = mode.maxResolution
        val workingBitmap = if (inputBitmap.width > maxDim || inputBitmap.height > maxDim) {
            val scale = maxDim.toFloat() / max(inputBitmap.width, inputBitmap.height)
            val newW = (inputBitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (inputBitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(inputBitmap, newW, newH, true)
        } else {
            inputBitmap
        }
        timings["preprocess"] = System.currentTimeMillis() - t0

        // 2. Feature Analysis & Classification
        onProgress("Analyzing", 0.15f, "Extracting edges, palette complexity & noise...")
        val t1 = System.currentTimeMillis()
        val features = ImageAnalyzer.analyze(workingBitmap)
        timings["analyze"] = System.currentTimeMillis() - t1

        // BUG 2 Fix: Wire classifier output dynamically into pipeline behavior
        val isOrganicOrPhoto = features.detectedCategory == com.example.core.model.ImageCategory.PHOTOGRAPH ||
                features.detectedCategory == com.example.core.model.ImageCategory.COMPLEX_ART

        // 3. Adaptive Color Quantization
        // Pre-quantization edge-preserving bilateral smoothing for photographs and complex artwork
        val bitmapToQuantize = if (isOrganicOrPhoto) {
            onProgress("Smoothing", 0.20f, "Edge-preserving noise suppression...")
            ColorQuantizer.applyEdgePreservingFilter(workingBitmap)
        } else {
            workingBitmap
        }

        // For PHOTOGRAPH/COMPLEX_ART, increase color budget substantially above flat defaults (40-64 colors)
        val effectiveColors = if (customSettings.colorLimit != null) {
            customSettings.colorLimit
        } else if (isOrganicOrPhoto) {
            (mode.colorLimit * 2).coerceIn(40, 64)
        } else {
            mode.colorLimit
        }

        onProgress("Color Quantization", 0.25f, "Clustering $effectiveColors optimal color planes...")
        val t2 = System.currentTimeMillis()
        var quantResult = ColorQuantizer.quantize(bitmapToQuantize, effectiveColors)

        // Region-merging pass: merge small noisy components into largest neighbor by shared perimeter
        val minRegionPixels = maxOf(4, (workingBitmap.width * workingBitmap.height * 0.0001).toInt())
        quantResult = ColorQuantizer.mergeMicroRegions(quantResult, minRegionPixels)
        timings["quantize"] = System.currentTimeMillis() - t2

        // 4. Contour Extraction & Hole Hierarchy
        onProgress("Tracing Contours", 0.40f, "Tracing boundaries & hole hierarchy...")
        val t3 = System.currentTimeMillis()

        val width = quantResult.width
        val height = quantResult.height
        val totalPixels = width * height

        // Scale minArea adaptively with image resolution
        val adaptiveMinArea = maxOf(4.0, totalPixels * 0.00005)

        // For photographic / organic content, guarantee sub-pixel refinement is active (subpixelIters >= 1)
        val subpixelIters = if (customSettings.subpixelIterations != null) {
            customSettings.subpixelIterations
        } else if (isOrganicOrPhoto) {
            maxOf(mode.subpixelIterations, 1)
        } else {
            mode.subpixelIterations
        }

        val rdpTolerance = customSettings.rdpTolerance ?: mode.rdpTolerance
        val cornerAngle = customSettings.cornerAngleDeg ?: 35.0

        // Force enableParametric = false and detectStars = false for photographs and complex artwork
        val enableParametric = if (isOrganicOrPhoto) {
            false
        } else {
            customSettings.enableParametric ?: mode.enableParametric
        }
        val detectStars = if (isOrganicOrPhoto) {
            false
        } else {
            customSettings.detectStars ?: mode.detectStars
        }

        // Collect refined contour data for all planes
        data class RawRegionHolder(
            val color: Int,
            val outerPts: List<com.example.core.model.SubpixelPoint>,
            val holesPts: List<List<com.example.core.model.SubpixelPoint>>,
            val bounds: RectD,
            val parametricShape: com.example.core.model.ParametricShape?
        )
        val rawRegions = ArrayList<RawRegionHolder>()

        // Iterate over color planes (excluding transparent if present)
        for (paletteIdx in quantResult.palette.indices) {
            val color = quantResult.palette[paletteIdx]
            if (color == Color.TRANSPARENT) continue

            // Build binary plane mask
            val planeMask = BooleanArray(totalPixels)
            var pixelCount = 0
            for (i in 0 until totalPixels) {
                if (quantResult.pixelClusterMap[i] == paletteIdx) {
                    planeMask[i] = true
                    pixelCount++
                }
            }
            if (pixelCount < minRegionPixels) continue // Skip micro noise

            // Extract outer contours and internal holes with adaptive minimum area
            val contours = ContourTracer.traceContours(planeMask, width, height, minArea = adaptiveMinArea)

            // Group holes under outer contours
            val outerContours = contours.filter { !it.isHole }
            val holeContours = contours.filter { it.isHole }

            for (outerIdx in outerContours.indices) {
                val outer = outerContours[outerIdx]

                // 5. Sub-pixel boundary refinement
                val refinedOuter = if (subpixelIters > 0) {
                    SubpixelRefiner.refine(outer, workingBitmap, color, iterations = subpixelIters)
                } else {
                    outer
                }

                // 7. Parametric shape detection (fully disabled for photographs and complex art)
                val outerShape = if (enableParametric) {
                    ParametricShapeDetector.detectShape(refinedOuter.points, rdpTolerance * 1.5, detectStars)
                } else null

                val associatedHoles = holeContours.filter { it.parentIndex == outerIdx }
                val refinedHolePtsList = associatedHoles.map { hole ->
                    if (subpixelIters > 0) {
                        SubpixelRefiner.refine(hole, workingBitmap, color, iterations = subpixelIters).points
                    } else hole.points
                }

                rawRegions.add(
                    RawRegionHolder(
                        color = color,
                        outerPts = refinedOuter.points,
                        holesPts = refinedHolePtsList,
                        bounds = refinedOuter.boundingBox,
                        parametricShape = outerShape
                    )
                )
            }
        }

        // 6. Curve fitting with hard path/node budget guard
        val maxNodeBudget = when (mode) {
            QualityMode.FAST -> 5000
            QualityMode.BALANCED -> 8000
            QualityMode.HIGH_QUALITY -> 12000
            QualityMode.ULTRA -> 16000
        }

        var currentRdp = rdpTolerance
        var passes = 0
        var allRegions = ArrayList<VectorRegion>()

        while (passes < 3) {
            allRegions = ArrayList(rawRegions.size)
            var regId = 0
            for (raw in rawRegions) {
                val outerSegs = CurveFitter.fitCurves(raw.outerPts, currentRdp, cornerAngle)
                if (outerSegs.isEmpty()) continue

                val holePaths = raw.holesPts.mapNotNull { hPts ->
                    val hSegs = CurveFitter.fitCurves(hPts, currentRdp, cornerAngle)
                    if (hSegs.isNotEmpty()) VectorPath(segments = hSegs, isHole = true) else null
                }

                allRegions.add(
                    VectorRegion(
                        id = ++regId,
                        color = raw.color,
                        outerPath = VectorPath(segments = outerSegs, isHole = false, parametricShape = raw.parametricShape),
                        holePaths = holePaths,
                        bounds = raw.bounds
                    )
                )
            }

            val totalNodes = allRegions.sumOf { it.outerPath.nodeCount + it.holePaths.sumOf { h -> h.nodeCount } }
            if (totalNodes <= maxNodeBudget || passes == 2) {
                break
            }
            currentRdp *= 1.6
            passes++
        }

        // Add base backing plane for solid (non-alpha) images to prevent any transparent checkerboard gaps
        if (!features.hasAlpha && allRegions.isNotEmpty()) {
            val dominantColor = quantResult.palette.firstOrNull { it != Color.TRANSPARENT } ?: workingBitmap.getPixel(0, 0)
            val bgSegments = listOf(
                com.example.core.model.PathSegment.Line(PointD(0.0, 0.0), PointD(width.toDouble(), 0.0)),
                com.example.core.model.PathSegment.Line(PointD(width.toDouble(), 0.0), PointD(width.toDouble(), height.toDouble())),
                com.example.core.model.PathSegment.Line(PointD(width.toDouble(), height.toDouble()), PointD(0.0, height.toDouble())),
                com.example.core.model.PathSegment.Line(PointD(0.0, height.toDouble()), PointD(0.0, 0.0))
            )
            allRegions.add(
                0,
                VectorRegion(
                    id = 0,
                    color = dominantColor,
                    outerPath = VectorPath(segments = bgSegments, isHole = false),
                    holePaths = emptyList(),
                    bounds = RectD(0.0, 0.0, width.toDouble(), height.toDouble()),
                    zIndex = 0
                )
            )
        }

        timings["contours_and_curves"] = System.currentTimeMillis() - t3

        // 8. Layer Stacking & Gap Bleed Correction
        onProgress("Layer & Gap Resolving", 0.70f, "Optimizing z-order & closing micro-gaps...")
        val t4 = System.currentTimeMillis()
        val gapCorrection = if (isOrganicOrPhoto) {
            0.0 // Blanket dilation disabled for photographic content to preserve fine details
        } else {
            customSettings.gapBleedCorrectionPx ?: mode.gapBleedCorrectionPx.coerceAtMost(0.12)
        }
        val layeredRegions = LayerStackResolver.resolveLayers(allRegions, gapBleedCorrectionPx = gapCorrection)
        timings["layers"] = System.currentTimeMillis() - t4

        // 9. Assemble VectorDocument & SVG String
        onProgress("SVG Serialization", 0.85f, "Writing allow-list sanitized vector markup...")
        val t5 = System.currentTimeMillis()
        val precision = customSettings.coordinatePrecision ?: mode.coordinatePrecision
        val vectorDoc = VectorDocument(
            width = width,
            height = height,
            regions = layeredRegions,
            title = "VectorForge_${System.currentTimeMillis()}"
        )
        val svgString = SvgWriter.toSvgString(vectorDoc, precision)
        timings["svg_write"] = System.currentTimeMillis() - t5

        // 10. Quality Validation & Re-Rasterization
        onProgress("Validating Quality", 0.95f, "Computing SSIM, Edge-IoU & PSNR scores...")
        val t6 = System.currentTimeMillis()
        val totalElapsed = System.currentTimeMillis() - startTime
        val report = QualityValidator.evaluateQuality(
            original = workingBitmap,
            document = vectorDoc,
            svgString = svgString,
            executionTimeMs = totalElapsed,
            timings = timings
        )
        val reRasterized = QualityValidator.rasterize(vectorDoc, 1.0f)
        timings["validation"] = System.currentTimeMillis() - t6

        onProgress("Complete", 1.0f, "Finished in ${totalElapsed}ms with SSIM ${(report.ssim * 100).toInt()}%")

        PipelineResult(
            document = vectorDoc,
            svgContent = svgString,
            qualityReport = report,
            features = features,
            reRasterizedBitmap = reRasterized,
            processedOriginalBitmap = workingBitmap
        )
    }
}
