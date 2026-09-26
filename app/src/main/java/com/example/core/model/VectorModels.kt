package com.example.core.model

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * High precision 2D point representation.
 */
data class PointD(val x: Double, val y: Double) {
    fun distanceTo(other: PointD): Double = hypot(x - other.x, y - other.y)

    operator fun plus(other: PointD): PointD = PointD(x + other.x, y + other.y)
    operator fun minus(other: PointD): PointD = PointD(x - other.x, y - other.y)
    operator fun times(scalar: Double): PointD = PointD(x * scalar, y * scalar)
    operator fun div(scalar: Double): PointD = PointD(x / scalar, y / scalar)

    fun length(): Double = hypot(x, y)

    fun normalized(): PointD {
        val len = length()
        return if (len > 1e-9) PointD(x / len, y / len) else PointD(0.0, 0.0)
    }

    fun normal(): PointD = PointD(-y, x).normalized()

    fun dot(other: PointD): Double = x * other.x + y * other.y
}

/**
 * Geometric segment along a vector path.
 */
sealed interface PathSegment {
    data class Line(val p0: PointD, val p1: PointD) : PathSegment

    data class CubicBezier(
        val p0: PointD,
        val p1: PointD,
        val p2: PointD,
        val p3: PointD
    ) : PathSegment
}

/**
 * Sub-pixel refined contour point with local normal and fit confidence.
 */
data class SubpixelPoint(
    val point: PointD,
    val normal: PointD = PointD(0.0, 0.0),
    val confidence: Double = 1.0
)

/**
 * Contour path containing ordered sub-pixel points.
 */
data class Contour(
    val points: List<SubpixelPoint>,
    val isHole: Boolean = false,
    val parentIndex: Int = -1,
    val boundingBox: RectD = calculateBounds(points)
) {
    companion object {
        fun calculateBounds(pts: List<SubpixelPoint>): RectD {
            if (pts.isEmpty()) return RectD(0.0, 0.0, 0.0, 0.0)
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in pts) {
                if (p.point.x < minX) minX = p.point.x
                if (p.point.y < minY) minY = p.point.y
                if (p.point.x > maxX) maxX = p.point.x
                if (p.point.y > maxY) maxY = p.point.y
            }
            return RectD(minX, minY, maxX, maxY)
        }
    }
}

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = (right - left).coerceAtLeast(0.0)
    val height: Double get() = (bottom - top).coerceAtLeast(0.0)
    val area: Double get() = width * height

    fun contains(other: RectD): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * Detected parametric shape representations.
 */
sealed interface ParametricShape {
    data class Circle(
        val cx: Double,
        val cy: Double,
        val radius: Double
    ) : ParametricShape

    data class Ellipse(
        val cx: Double,
        val cy: Double,
        val rx: Double,
        val ry: Double,
        val rotationDeg: Double = 0.0
    ) : ParametricShape

    data class RegularPolygon(
        val cx: Double,
        val cy: Double,
        val radius: Double,
        val sides: Int,
        val rotationDeg: Double = 0.0
    ) : ParametricShape

    data class Star(
        val cx: Double,
        val cy: Double,
        val outerRadius: Double,
        val innerRadius: Double,
        val points: Int = 5,
        val rotationDeg: Double = 0.0
    ) : ParametricShape
}

/**
 * A continuous vector path with potential parametric representation.
 */
data class VectorPath(
    val segments: List<PathSegment>,
    val isHole: Boolean = false,
    val parametricShape: ParametricShape? = null
) {
    val nodeCount: Int
        get() = when (parametricShape) {
            is ParametricShape.Circle -> 4
            is ParametricShape.Ellipse -> 4
            is ParametricShape.RegularPolygon -> parametricShape.sides
            is ParametricShape.Star -> parametricShape.points * 2
            null -> segments.size + 1
        }
}

/**
 * Logical colored region consisting of outer boundary and internal holes.
 */
data class VectorRegion(
    val id: Int,
    val color: Int, // ARGB int
    val outerPath: VectorPath,
    val holePaths: List<VectorPath> = emptyList(),
    val bounds: RectD,
    val zIndex: Int = 0
)

/**
 * Complete vector document.
 */
data class VectorDocument(
    val width: Int,
    val height: Int,
    val regions: List<VectorRegion>,
    val title: String = "VectorGraphic"
) {
    val totalPaths: Int get() = regions.sumOf { 1 + it.holePaths.size }
    val totalNodes: Int get() = regions.sumOf { it.outerPath.nodeCount + it.holePaths.sumOf { h -> h.nodeCount } }
}

/**
 * Quality validation report containing real computed metrics.
 */
data class QualityReport(
    val ssim: Double,
    val edgeIoU: Double,
    val psnr: Double,
    val pathCount: Int,
    val nodeCount: Int,
    val fileSizeBytes: Long,
    val executionTimeMs: Long,
    val stageTimings: Map<String, Long> = emptyMap()
)

/**
 * Image classification & feature analysis.
 */
enum class ImageCategory(val displayName: String) {
    LOGO("Logo & Branding"),
    ICON("Monochrome / UI Icon"),
    FLAT_ILLUSTRATION("Flat Illustration"),
    LINE_DRAWING("Line Art / Sketch"),
    STICKER("Sticker / Badge"),
    TYPOGRAPHY("Typography / Lettering"),
    COMPLEX_ART("Complex Artwork"),
    PHOTOGRAPH("Photograph / Realistic")
}

data class ImageFeatures(
    val edgeDensity: Double,
    val uniqueColors: Int,
    val hasAlpha: Boolean,
    val softAlphaRatio: Double,
    val noiseScore: Double,
    val detectedCategory: ImageCategory,
    val confidence: Double
)

/**
 * Preset quality modes matching the specification.
 */
enum class QualityMode(
    val label: String,
    val description: String,
    val maxResolution: Int,
    val colorLimit: Int,
    val rdpTolerance: Double,
    val subpixelIterations: Int,
    val enableParametric: Boolean,
    val detectStars: Boolean,
    val gapBleedCorrectionPx: Double,
    val coordinatePrecision: Int
) {
    FAST(
        label = "Fast",
        description = "Rapid tracing, optimal for quick icon drafts (<1s)",
        maxResolution = 1024,
        colorLimit = 16,
        rdpTolerance = 1.6,
        subpixelIterations = 0,
        enableParametric = false,
        detectStars = false,
        gapBleedCorrectionPx = 0.8,
        coordinatePrecision = 2
    ),
    BALANCED(
        label = "Balanced",
        description = "Coverage sub-pixel estimation & shapes (2-3s)",
        maxResolution = 2048,
        colorLimit = 32,
        rdpTolerance = 1.0,
        subpixelIterations = 1,
        enableParametric = true,
        detectStars = false,
        gapBleedCorrectionPx = 0.5,
        coordinatePrecision = 2
    ),
    HIGH_QUALITY(
        label = "High Quality",
        description = "Sigmoid sub-pixel fitting, fine curves & holes",
        maxResolution = 3072,
        colorLimit = 48,
        rdpTolerance = 0.65,
        subpixelIterations = 2,
        enableParametric = true,
        detectStars = false,
        gapBleedCorrectionPx = 0.35,
        coordinatePrecision = 3
    ),
    ULTRA(
        label = "Ultra Fidelity",
        description = "Full iterative boundary convergence & micro-gap correction",
        maxResolution = 4096,
        colorLimit = 64,
        rdpTolerance = 0.4,
        subpixelIterations = 3,
        enableParametric = true,
        detectStars = true,
        gapBleedCorrectionPx = 0.25,
        coordinatePrecision = 3
    )
}
