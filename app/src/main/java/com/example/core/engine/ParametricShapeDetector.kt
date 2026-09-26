package com.example.core.engine

import com.example.core.model.ParametricShape
import com.example.core.model.PointD
import com.example.core.model.SubpixelPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 7: Parametric Shape Detection (Circle, Ellipse, Regular Polygons, Stars).
 * Fixed BUG 1: Non-tautological least-squares parameter estimation with 80/20 train/test
 * cross-validation and hard absolute pixel tolerance ceiling.
 */
object ParametricShapeDetector {

    // Hard absolute pixel-tolerance ceiling to prevent permissive fits on large/complex contours
    const val MAX_ABSOLUTE_TOLERANCE_PX = 1.6

    data class ShapeDetectionResult(
        val shape: ParametricShape,
        val meanError: Double,
        val maxError: Double
    )

    fun detectShape(
        points: List<SubpixelPoint>,
        tolerance: Double = 1.2,
        detectStars: Boolean = true
    ): ParametricShape? {
        if (points.size < 10) return null
        val pts = points.map { it.point }

        // Hard tolerance ceiling applied
        val effectiveTolerance = tolerance.coerceIn(0.2, MAX_ABSOLUTE_TOLERANCE_PX)

        // Split points into 80% train and 20% test (held-out) for cross-validation
        val trainPts = ArrayList<PointD>()
        val testPts = ArrayList<PointD>()
        for (i in pts.indices) {
            if (i % 5 == 0) {
                testPts.add(pts[i])
            } else {
                trainPts.add(pts[i])
            }
        }
        if (trainPts.size < 6 || testPts.size < 2) return null

        // 1. Try Circle Fitting (Kåsa least squares on train, cross-validated on test)
        val circleResult = fitCircle(trainPts, testPts, pts, effectiveTolerance)
        if (circleResult != null) {
            return circleResult.shape
        }

        // 2. Try Ellipse Fitting (Direct algebraic conic least squares, cross-validated)
        val ellipseResult = fitEllipse(trainPts, testPts, pts, effectiveTolerance)
        if (ellipseResult != null) {
            val el = ellipseResult.shape as ParametricShape.Ellipse
            val ratio = if (el.rx > el.ry) el.rx / el.ry else el.ry / el.rx
            // If aspect ratio is close to 1, circle is preferred
            if (ratio > 1.15) {
                return ellipseResult.shape
            }
        }

        // 3. Try Regular Polygons (3, 4, 5, 6, 8 sides, cross-validated)
        val polygonResult = fitRegularPolygon(trainPts, testPts, pts, listOf(3, 4, 5, 6, 8), effectiveTolerance)
        if (polygonResult != null) {
            return polygonResult.shape
        }

        // 4. Try Star Shapes (5, 6, 8 points, cross-validated)
        if (detectStars) {
            val starResult = fitStar(trainPts, testPts, pts, listOf(5, 6, 8), effectiveTolerance)
            if (starResult != null) {
                return starResult.shape
            }
        }

        return null
    }

    private fun fitCircle(
        trainPts: List<PointD>,
        testPts: List<PointD>,
        allPts: List<PointD>,
        tolerance: Double
    ): ShapeDetectionResult? {
        val n = trainPts.size
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var sumXY = 0.0
        var sumR3X = 0.0
        var sumR3Y = 0.0

        for (p in trainPts) {
            val x = p.x
            val y = p.y
            val r2 = x * x + y * y
            sumX += x
            sumY += y
            sumX2 += x * x
            sumY2 += y * y
            sumXY += x * y
            sumR3X += x * r2
            sumR3Y += y * r2
        }

        val a = n * sumX2 - sumX * sumX
        val b = n * sumXY - sumX * sumY
        val c = n * sumY2 - sumY * sumY
        val d = 0.5 * (n * sumR3X - sumX * (sumX2 + sumY2))
        val e = 0.5 * (n * sumR3Y - sumY * (sumX2 + sumY2))

        val det = a * c - b * b
        if (abs(det) < 1e-9) return null

        val cx = (d * c - b * e) / det
        val cy = (a * e - b * d) / det

        var radiusSum = 0.0
        for (p in trainPts) {
            radiusSum += hypot(p.x - cx, p.y - cy)
        }
        val radius = radiusSum / n
        if (radius < 3.0) return null

        // Cross-validation on held-out test points
        var testErrSum = 0.0
        var testMaxErr = 0.0
        for (p in testPts) {
            val err = abs(hypot(p.x - cx, p.y - cy) - radius)
            testErrSum += err
            if (err > testMaxErr) testMaxErr = err
        }
        val testMeanErr = testErrSum / testPts.size
        if (testMeanErr > tolerance || testMaxErr > tolerance * 2.0) {
            return null
        }

        // Full validation error
        var fullErrSum = 0.0
        var fullMaxErr = 0.0
        for (p in allPts) {
            val err = abs(hypot(p.x - cx, p.y - cy) - radius)
            fullErrSum += err
            if (err > fullMaxErr) fullMaxErr = err
        }
        val fullMeanErr = fullErrSum / allPts.size
        if (fullMeanErr > tolerance || fullMaxErr > tolerance * 2.0) {
            return null
        }

        return ShapeDetectionResult(
            shape = ParametricShape.Circle(cx, cy, radius),
            meanError = fullMeanErr,
            maxError = fullMaxErr
        )
    }

    /**
     * Genuine algebraic direct least-squares ellipse fit over A x'^2 + B x' y' + C y'^2 + D x' + E y' = 1
     * centered at the centroid, converting to canonical (cx, cy, rx, ry, rotation).
     * Does NOT derive rx/ry from point extremes.
     */
    private fun fitEllipse(
        trainPts: List<PointD>,
        testPts: List<PointD>,
        allPts: List<PointD>,
        tolerance: Double
    ): ShapeDetectionResult? {
        val x0 = trainPts.map { it.x }.average()
        val y0 = trainPts.map { it.y }.average()
        val scale = trainPts.map { hypot(it.x - x0, it.y - y0) }.average()
        if (scale < 3.0) return null

        // Build 5x5 normal equation matrix M and RHS vector Y with normalized coordinates
        val m = Array(5) { DoubleArray(5) }
        val rhs = DoubleArray(5)

        for (p in trainPts) {
            val u = (p.x - x0) / scale
            val v = (p.y - y0) / scale
            val row = doubleArrayOf(u * u, u * v, v * v, u, v)
            for (r in 0 until 5) {
                rhs[r] += row[r]
                for (c in 0 until 5) {
                    m[r][c] += row[r] * row[c]
                }
            }
        }

        val sol = solve5x5(m, rhs) ?: return null
        // Unscale parameters back to pixel units
        val a = sol[0] / (scale * scale)
        val b = sol[1] / (scale * scale)
        val c = sol[2] / (scale * scale)
        val d = sol[3] / scale
        val e = sol[4] / scale

        // Ellipse condition: Discriminant delta = b^2 - 4ac < 0
        val delta = b * b - 4.0 * a * c
        if (delta >= -1e-7 || a <= 0.0 || c <= 0.0) {
            return null // Not an ellipse (hyperbola/parabola/degenerate)
        }

        // Center relative to (x0, y0)
        val uc = (b * e - 2.0 * c * d) / (-delta)
        val vc = (b * d - 2.0 * a * e) / (-delta)
        val cx = x0 + uc
        val cy = y0 + vc

        // Value of algebraic equation at center
        val fVal = a * uc * uc + b * uc * vc + c * vc * vc + d * uc + e * vc - 1.0
        val minusF = -fVal
        if (minusF <= 1e-7) return null

        val rootTerm = sqrt((a - c) * (a - c) + b * b)
        val denom1 = a + c + rootTerm
        val denom2 = a + c - rootTerm
        if (denom1 <= 1e-7 || denom2 <= 1e-7) return null

        // In canonical coordinate system with theta = 0.5 * atan2(b, a - c):
        // Along the rotated x-axis (angle theta), the coefficient is denom1 / 2, so rx = sqrt(2 * minusF / denom1).
        // Along the rotated y-axis (angle theta + 90°), the coefficient is denom2 / 2, so ry = sqrt(2 * minusF / denom2).
        val rx = sqrt(2.0 * minusF / denom1)
        val ry = sqrt(2.0 * minusF / denom2)
        if (rx < 3.0 || ry < 3.0 || rx.isNaN() || ry.isNaN()) return null
        if (rx / ry > 8.0 || ry / rx > 8.0) return null

        val rotRad = 0.5 * atan2(b, a - c)
        val cosRot = cos(rotRad)
        val sinRot = sin(rotRad)

        fun pointDistanceToEllipse(pt: PointD): Double {
            val dx = pt.x - cx
            val dy = pt.y - cy
            val xLocal = dx * cosRot + dy * sinRot
            val yLocal = -dx * sinRot + dy * cosRot
            val theta = atan2(yLocal, xLocal)
            val cosT = cos(theta)
            val sinT = sin(theta)
            val expectedR = (rx * ry) / sqrt((ry * cosT) * (ry * cosT) + (rx * sinT) * (rx * sinT))
            val actualR = hypot(xLocal, yLocal)
            return abs(actualR - expectedR)
        }

        // Cross-validation on held-out test points
        var testErrSum = 0.0
        var testMaxErr = 0.0
        for (p in testPts) {
            val err = pointDistanceToEllipse(p)
            testErrSum += err
            if (err > testMaxErr) testMaxErr = err
        }
        val testMeanErr = testErrSum / testPts.size
        if (testMeanErr > tolerance || testMaxErr > tolerance * 2.0) {
            return null
        }

        // Full validation
        var fullErrSum = 0.0
        var fullMaxErr = 0.0
        for (p in allPts) {
            val err = pointDistanceToEllipse(p)
            fullErrSum += err
            if (err > fullMaxErr) fullMaxErr = err
        }
        val fullMeanErr = fullErrSum / allPts.size
        if (fullMeanErr > tolerance || fullMaxErr > tolerance * 2.0) {
            return null
        }

        return ShapeDetectionResult(
            shape = ParametricShape.Ellipse(cx, cy, rx, ry, Math.toDegrees(rotRad)),
            meanError = fullMeanErr,
            maxError = fullMaxErr
        )
    }

    private fun fitRegularPolygon(
        trainPts: List<PointD>,
        testPts: List<PointD>,
        allPts: List<PointD>,
        sideCandidates: List<Int>,
        tolerance: Double
    ): ShapeDetectionResult? {
        val cx = trainPts.map { it.x }.average()
        val cy = trainPts.map { it.y }.average()
        val baseR = trainPts.map { hypot(it.x - cx, it.y - cy) }.average()
        if (baseR < 4.0) return null

        for (sides in sideCandidates) {
            val angleStep = (2.0 * Math.PI) / sides
            val halfStep = angleStep / 2.0

            for (step in 0 until 12) {
                val rot = (step.toDouble() / 12.0) * angleStep

                // Fit apothem R_apothem on trainPts by least squares: 1/r_i = (1/R_apothem) * cos(beta_i)
                var sumCosDivR = 0.0
                var sumCos2 = 0.0
                for (p in trainPts) {
                    val theta = atan2(p.y - cy, p.x - cx) - rot
                    val normTheta = ((theta % angleStep) + angleStep) % angleStep
                    val beta = abs(normTheta - halfStep)
                    val r = hypot(p.x - cx, p.y - cy)
                    val cosB = cos(beta)
                    if (r > 1e-4) {
                        sumCosDivR += cosB / r
                        sumCos2 += cosB * cosB
                    }
                }
                if (sumCos2 < 1e-6 || sumCosDivR < 1e-6) continue

                val invApothem = sumCosDivR / sumCos2
                val apothem = 1.0 / invApothem
                val radius = apothem / cos(halfStep)
                if (radius < 3.0) continue

                fun polyDist(p: PointD): Double {
                    val theta = atan2(p.y - cy, p.x - cx) - rot
                    val normTheta = ((theta % angleStep) + angleStep) % angleStep
                    val beta = abs(normTheta - halfStep)
                    val expectedR = apothem / cos(beta)
                    val actualR = hypot(p.x - cx, p.y - cy)
                    return abs(actualR - expectedR)
                }

                // Cross-validation on held-out test points
                var testErrSum = 0.0
                var testMaxErr = 0.0
                for (p in testPts) {
                    val err = polyDist(p)
                    testErrSum += err
                    if (err > testMaxErr) testMaxErr = err
                }
                val testMean = testErrSum / testPts.size
                if (testMean > tolerance || testMaxErr > tolerance * 2.0) continue

                // Check full points
                var fullErrSum = 0.0
                var fullMaxErr = 0.0
                for (p in allPts) {
                    val err = polyDist(p)
                    fullErrSum += err
                    if (err > fullMaxErr) fullMaxErr = err
                }
                val fullMean = fullErrSum / allPts.size
                if (fullMean <= tolerance && fullMaxErr <= tolerance * 2.0) {
                    return ShapeDetectionResult(
                        shape = ParametricShape.RegularPolygon(cx, cy, radius, sides, Math.toDegrees(rot)),
                        meanError = fullMean,
                        maxError = fullMaxErr
                    )
                }
            }
        }
        return null
    }

    private fun fitStar(
        trainPts: List<PointD>,
        testPts: List<PointD>,
        allPts: List<PointD>,
        pointCandidates: List<Int>,
        tolerance: Double
    ): ShapeDetectionResult? {
        val cx = trainPts.map { it.x }.average()
        val cy = trainPts.map { it.y }.average()

        for (numPoints in pointCandidates) {
            val totalRays = numPoints * 2
            val rayAngle = (2.0 * Math.PI) / totalRays

            for (step in 0 until 12) {
                val rot = (step.toDouble() / 12.0) * rayAngle

                // Solve linear least squares for u = 1/R_outer and v = 1/R_inner on train points
                var s11 = 0.0
                var s12 = 0.0
                var s22 = 0.0
                var y1 = 0.0
                var y2 = 0.0

                val sinRay = sin(rayAngle)
                if (abs(sinRay) < 1e-6) continue

                for (p in trainPts) {
                    val theta = ((atan2(p.y - cy, p.x - cx) - rot) % (2.0 * Math.PI) + 2.0 * Math.PI) % (2.0 * Math.PI)
                    val segmentTheta = theta % rayAngle
                    val r = hypot(p.x - cx, p.y - cy)
                    if (r < 1e-4) continue

                    val rayIdx = (theta / rayAngle).toInt() % totalRays
                    val (w1, w2) = if (rayIdx % 2 == 0) {
                        sin(rayAngle - segmentTheta) / sinRay to sin(segmentTheta) / sinRay
                    } else {
                        sin(segmentTheta) / sinRay to sin(rayAngle - segmentTheta) / sinRay
                    }

                    s11 += w1 * w1
                    s12 += w1 * w2
                    s22 += w2 * w2
                    val invR = 1.0 / r
                    y1 += w1 * invR
                    y2 += w2 * invR
                }

                val det = s11 * s22 - s12 * s12
                if (abs(det) < 1e-7) continue

                val u = (y1 * s22 - s12 * y2) / det
                val v = (s11 * y2 - y1 * s12) / det
                if (u <= 1e-6 || v <= 1e-6) continue

                val outerR = 1.0 / u
                val innerR = 1.0 / v

                // Must be a genuine star with noticeable star points
                if (outerR / innerR < 1.30 || innerR < 3.0) continue

                fun starDist(p: PointD): Double {
                    val theta = ((atan2(p.y - cy, p.x - cx) - rot) % (2.0 * Math.PI) + 2.0 * Math.PI) % (2.0 * Math.PI)
                    val segmentTheta = theta % rayAngle
                    val rayIdx = (theta / rayAngle).toInt() % totalRays
                    val (w1, w2) = if (rayIdx % 2 == 0) {
                        sin(rayAngle - segmentTheta) / sinRay to sin(segmentTheta) / sinRay
                    } else {
                        sin(segmentTheta) / sinRay to sin(rayAngle - segmentTheta) / sinRay
                    }
                    val expectedInvR = u * w1 + v * w2
                    val expectedR = if (expectedInvR > 1e-6) 1.0 / expectedInvR else outerR
                    val actualR = hypot(p.x - cx, p.y - cy)
                    return abs(actualR - expectedR)
                }

                // Cross-validation on held-out test points
                var testErrSum = 0.0
                var testMaxErr = 0.0
                for (p in testPts) {
                    val err = starDist(p)
                    testErrSum += err
                    if (err > testMaxErr) testMaxErr = err
                }
                val testMean = testErrSum / testPts.size
                if (testMean > tolerance || testMaxErr > tolerance * 2.0) continue

                // Check full points
                var fullErrSum = 0.0
                var fullMaxErr = 0.0
                for (p in allPts) {
                    val err = starDist(p)
                    fullErrSum += err
                    if (err > fullMaxErr) fullMaxErr = err
                }
                val fullMean = fullErrSum / allPts.size
                if (fullMean <= tolerance && fullMaxErr <= tolerance * 2.0) {
                    return ShapeDetectionResult(
                        shape = ParametricShape.Star(cx, cy, outerR, innerR, numPoints, Math.toDegrees(rot)),
                        meanError = fullMean,
                        maxError = fullMaxErr
                    )
                }
            }
        }
        return null
    }

    private fun solve5x5(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 5
        val mat = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }

        for (i in 0 until n) {
            var maxRow = i
            var maxVal = abs(mat[i][i])
            for (k in i + 1 until n) {
                if (abs(mat[k][i]) > maxVal) {
                    maxVal = abs(mat[k][i])
                    maxRow = k
                }
            }
            if (maxVal < 1e-12) return null

            val temp = mat[i]
            mat[i] = mat[maxRow]
            mat[maxRow] = temp

            val pivot = mat[i][i]
            for (j in i until n + 1) {
                mat[i][j] /= pivot
            }

            for (k in 0 until n) {
                if (k != i) {
                    val factor = mat[k][i]
                    for (j in i until n + 1) {
                        mat[k][j] -= factor * mat[i][j]
                    }
                }
            }
        }

        return DoubleArray(n) { mat[it][n] }
    }
}
