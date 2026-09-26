package com.example.core.engine

import com.example.core.model.PathSegment
import com.example.core.model.PointD
import com.example.core.model.SubpixelPoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Stage 6: Curve Reconstruction & Bézier Fitting.
 * Implements Schneider's algorithm (Graphics Gems I) and curvature-aware RDP.
 */
object CurveFitter {

    /**
     * Fits smooth cubic Bézier curves and straight lines to a contour of points.
     */
    fun fitCurves(
        points: List<SubpixelPoint>,
        tolerance: Double = 1.0,
        cornerAngleThresholdDeg: Double = 35.0
    ): List<PathSegment> {
        if (points.size < 2) return emptyList()
        val rawPts = points.map { it.point }

        // 1. RDP polyline simplification
        val simplifiedIndices = rdpSimplify(rawPts, tolerance * 0.5)
        if (simplifiedIndices.size < 2) return emptyList()

        // 2. Corner detection based on turning angles
        val corners = HashSet<Int>()
        corners.add(0)
        corners.add(simplifiedIndices.size - 1)

        val radThreshold = Math.toRadians(cornerAngleThresholdDeg)
        for (i in 1 until simplifiedIndices.size - 1) {
            val prevPt = rawPts[simplifiedIndices[i - 1]]
            val currPt = rawPts[simplifiedIndices[i]]
            val nextPt = rawPts[simplifiedIndices[i + 1]]

            val v1 = (currPt - prevPt).normalized()
            val v2 = (nextPt - currPt).normalized()
            val dot = v1.dot(v2).coerceIn(-1.0, 1.0)
            val turningAngle = acos(dot)

            if (turningAngle >= radThreshold) {
                corners.add(i)
            }
        }

        // 3. Segment into smooth runs between corners and fit Schneider's Béziers
        val segments = ArrayList<PathSegment>()
        val sortedCorners = corners.sorted()

        for (cIdx in 0 until sortedCorners.size - 1) {
            val startSimpleIdx = sortedCorners[cIdx]
            val endSimpleIdx = sortedCorners[cIdx + 1]

            val startRawIdx = simplifiedIndices[startSimpleIdx]
            val endRawIdx = simplifiedIndices[endSimpleIdx]

            val subPoints = rawPts.subList(startRawIdx, endRawIdx + 1)
            if (subPoints.size <= 2) {
                segments.add(PathSegment.Line(subPoints.first(), subPoints.last()))
            } else {
                fitCubicRun(subPoints, tolerance, segments)
            }
        }

        // Close loop if start and end are not coincident
        if (rawPts.first().distanceTo(rawPts.last()) > 1e-4) {
            segments.add(PathSegment.Line(rawPts.last(), rawPts.first()))
        }

        return segments
    }

    /**
     * Ramer-Douglas-Peucker polyline simplification.
     */
    private fun rdpSimplify(pts: List<PointD>, epsilon: Double): List<Int> {
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true

        fun rdpRecursive(startIndex: Int, endIndex: Int) {
            if (endIndex <= startIndex + 1) return

            var maxDist = 0.0
            var maxIndex = startIndex
            val start = pts[startIndex]
            val end = pts[endIndex]

            for (i in startIndex + 1 until endIndex) {
                val d = perpendicularDistance(pts[i], start, end)
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }

            if (maxDist > epsilon) {
                keep[maxIndex] = true
                rdpRecursive(startIndex, maxIndex)
                rdpRecursive(maxIndex, endIndex)
            }
        }

        rdpRecursive(0, pts.size - 1)
        val result = ArrayList<Int>()
        for (i in pts.indices) {
            if (keep[i]) result.add(i)
        }
        return result
    }

    private fun perpendicularDistance(p: PointD, a: PointD, b: PointD): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return p.distanceTo(a)

        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        val clampedT = t.coerceIn(0.0, 1.0)
        val proj = PointD(a.x + clampedT * dx, a.y + clampedT * dy)
        return p.distanceTo(proj)
    }

    /**
     * Fits a cubic Bézier curve to a smooth sequence of points using Schneider's method.
     */
    private fun fitCubicRun(
        pts: List<PointD>,
        tolerance: Double,
        outSegments: MutableList<PathSegment>
    ) {
        val p0 = pts.first()
        val p3 = pts.last()
        val n = pts.size

        // Estimate end tangents
        val tHat1 = (pts[1] - p0).normalized()
        val tHat2 = (p3 - pts[n - 2]).normalized()

        val chordLengths = DoubleArray(n)
        chordLengths[0] = 0.0
        for (i in 1 until n) {
            chordLengths[i] = chordLengths[i - 1] + pts[i].distanceTo(pts[i - 1])
        }
        val totalLength = chordLengths.last()
        if (totalLength < 1e-6) {
            outSegments.add(PathSegment.Line(p0, p3))
            return
        }

        // Parameterize u_i by chord length
        val u = DoubleArray(n) { i -> chordLengths[i] / totalLength }

        // Fit single cubic
        val bezier = generateBezier(pts, u, tHat1, tHat2)
        var maxError = 0.0
        var splitPoint = n / 2

        for (i in 1 until n - 1) {
            val eval = evaluateBezier(bezier, u[i])
            val dist = pts[i].distanceTo(eval)
            if (dist > maxError) {
                maxError = dist
                splitPoint = i
            }
        }

        if (maxError <= tolerance || n <= 4) {
            outSegments.add(bezier)
        } else {
            // Recursive subdivision at point of maximum error
            fitCubicRun(pts.subList(0, splitPoint + 1), tolerance, outSegments)
            fitCubicRun(pts.subList(splitPoint, n), tolerance, outSegments)
        }
    }

    private fun generateBezier(
        pts: List<PointD>,
        u: DoubleArray,
        tHat1: PointD,
        tHat2: PointD
    ): PathSegment.CubicBezier {
        val p0 = pts.first()
        val p3 = pts.last()
        val n = pts.size

        var c11 = 0.0
        var c12 = 0.0
        var c22 = 0.0
        var x1 = 0.0
        var x2 = 0.0

        for (i in 0 until n) {
            val t = u[i]
            val b0 = (1 - t) * (1 - t) * (1 - t)
            val b1 = 3 * t * (1 - t) * (1 - t)
            val b2 = 3 * t * t * (1 - t)
            val b3 = t * t * t

            val a1 = tHat1 * b1
            val a2 = tHat2 * b2

            c11 += a1.dot(a1)
            c12 += a1.dot(a2)
            c22 += a2.dot(a2)

            val tmp = pts[i] - (p0 * (b0 + b1)) - (p3 * (b2 + b3))
            x1 += a1.dot(tmp)
            x2 += a2.dot(tmp)
        }

        val det = c11 * c22 - c12 * c12
        val (alphaL, alphaR) = if (abs(det) > 1e-9) {
            val aL = (x1 * c22 - c12 * x2) / det
            val aR = (c11 * x2 - x1 * c12) / det
            aL to aR
        } else {
            val dist = p0.distanceTo(p3) / 3.0
            dist to dist
        }

        val safeAlphaL = if (alphaL <= 0.0) p0.distanceTo(p3) / 3.0 else alphaL
        val safeAlphaR = if (alphaR <= 0.0) p0.distanceTo(p3) / 3.0 else alphaR

        val p1 = p0 + (tHat1 * safeAlphaL)
        val p2 = p3 - (tHat2 * safeAlphaR)

        return PathSegment.CubicBezier(p0, p1, p2, p3)
    }

    private fun evaluateBezier(bezier: PathSegment.CubicBezier, t: Double): PointD {
        val invT = 1.0 - t
        val b0 = invT * invT * invT
        val b1 = 3 * t * invT * invT
        val b2 = 3 * t * t * invT
        val b3 = t * t * t
        return PointD(
            b0 * bezier.p0.x + b1 * bezier.p1.x + b2 * bezier.p2.x + b3 * bezier.p3.x,
            b0 * bezier.p0.y + b1 * bezier.p1.y + b2 * bezier.p2.y + b3 * bezier.p3.y
        )
    }
}
