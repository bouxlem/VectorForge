package com.example.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.model.Contour
import com.example.core.model.PointD
import com.example.core.model.SubpixelPoint
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 5: Coverage-Based Marching Squares & Sigmoid Sub-Pixel Boundary Refinement.
 * Implements VECTOR_ALGORITHMS.md §5.
 */
object SubpixelRefiner {

    /**
     * Refines the contour vertices to sub-pixel precision using continuous edge coverage fields.
     */
    fun refine(
        contour: Contour,
        bitmap: Bitmap,
        targetColor: Int,
        iterations: Int = 1
    ): Contour {
        if (iterations <= 0 || contour.points.size < 3) return contour

        val width = bitmap.width
        val height = bitmap.height
        var currentPoints = contour.points

        for (it in 0 until iterations) {
            val refined = ArrayList<SubpixelPoint>(currentPoints.size)

            for (i in currentPoints.indices) {
                val prev = currentPoints[(i - 1 + currentPoints.size) % currentPoints.size].point
                val curr = currentPoints[i].point
                val next = currentPoints[(i + 1) % currentPoints.size].point

                // 1. Calculate refined normal from incoming and outgoing tangent vectors
                val t1 = (curr - prev).normalized()
                val t2 = (next - curr).normalized()
                val tangent = (t1 + t2).normalized()
                // Normal perpendicular to tangent (pointing outward)
                val normal = if (contour.isHole) {
                    PointD(tangent.y, -tangent.x)
                } else {
                    PointD(-tangent.y, tangent.x)
                }

                // 2. Sample continuous coverage field f(t) along normal: t in [-1.5, +1.5]
                val sampleDistances = doubleArrayOf(-1.5, -0.75, 0.0, 0.75, 1.5)
                val coverageSamples = DoubleArray(sampleDistances.size)

                for (sIdx in sampleDistances.indices) {
                    val samplePt = curr + (normal * sampleDistances[sIdx])
                    coverageSamples[sIdx] = sampleContinuousCoverage(bitmap, samplePt, targetColor, width, height)
                }

                // 3. Fit sigmoid edge crossing t0
                val (t0, confidence) = fitSigmoidCrossing(sampleDistances, coverageSamples)

                // Clamp sub-pixel shift to [-1.0, 1.0] pixel range for numerical stability
                val clampedOffset = t0.coerceIn(-1.0, 1.0)
                val refinedPoint = curr + (normal * clampedOffset)

                refined.add(
                    SubpixelPoint(
                        point = refinedPoint,
                        normal = normal,
                        confidence = confidence
                    )
                )
            }
            currentPoints = refined
        }

        return contour.copy(
            points = currentPoints,
            boundingBox = Contour.calculateBounds(currentPoints)
        )
    }

    /**
     * Bilinear interpolation of continuous coverage (alpha or color similarity).
     */
    private fun sampleContinuousCoverage(
        bitmap: Bitmap,
        pt: PointD,
        targetColor: Int,
        width: Int,
        height: Int
    ): Double {
        val x = pt.x.coerceIn(0.0, (width - 1).toDouble())
        val y = pt.y.coerceIn(0.0, (height - 1).toDouble())

        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)

        val fx = x - x0
        val fy = y - y0

        val c00 = coverageAt(bitmap, x0, y0, targetColor)
        val c10 = coverageAt(bitmap, x1, y0, targetColor)
        val c01 = coverageAt(bitmap, x0, y1, targetColor)
        val c11 = coverageAt(bitmap, x1, y1, targetColor)

        val top = (1.0 - fx) * c00 + fx * c10
        val bottom = (1.0 - fx) * c01 + fx * c11
        return (1.0 - fy) * top + fy * bottom
    }

    private fun coverageAt(bitmap: Bitmap, x: Int, y: Int, targetColor: Int): Double {
        val pixel = bitmap.getPixel(x, y)
        val alpha = Color.alpha(pixel) / 255.0

        if (targetColor == Color.TRANSPARENT) {
            return 1.0 - alpha
        }

        // Color similarity distance in RGB normalized to [0, 1]
        val dr = (Color.red(pixel) - Color.red(targetColor)) / 255.0
        val dg = (Color.green(pixel) - Color.green(targetColor)) / 255.0
        val db = (Color.blue(pixel) - Color.blue(targetColor)) / 255.0
        val dist = kotlin.math.sqrt(dr * dr + dg * dg + db * db) / kotlin.math.sqrt(3.0)

        val similarity = (1.0 - dist).coerceIn(0.0, 1.0)
        return similarity * alpha
    }

    /**
     * Solves for the 0.5-crossing t0 of the sigmoid edge response profile:
     * f(t) = 1 / (1 + exp(-k * (t - t0)))
     * => ln(f / (1 - f)) = k * t - k * t0 = A * t + B
     * where t0 = -B / A.
     */
    private fun fitSigmoidCrossing(
        distances: DoubleArray,
        samples: DoubleArray
    ): Pair<Double, Double> {
        var sumT = 0.0
        var sumY = 0.0
        var sumTT = 0.0
        var sumTY = 0.0
        var validCount = 0

        for (i in distances.indices) {
            val t = distances[i]
            val s = samples[i].coerceIn(0.01, 0.99) // Clip away from asymptotes
            val logit = ln(s / (1.0 - s))

            sumT += t
            sumY += logit
            sumTT += t * t
            sumTY += t * logit
            validCount++
        }

        if (validCount < 3) return 0.0 to 0.5

        val denom = validCount * sumTT - sumT * sumT
        if (abs(denom) < 1e-9) return 0.0 to 0.5

        val slopeA = (validCount * sumTY - sumT * sumY) / denom
        val interceptB = (sumY - slopeA * sumT) / validCount

        if (abs(slopeA) < 1e-4) return 0.0 to 0.5

        // t0 = -interceptB / slopeA
        val t0 = -interceptB / slopeA

        // Confidence based on steepness of edge slope
        val confidence = (abs(slopeA) / 2.0).coerceIn(0.3, 1.0)
        return t0 to confidence
    }
}
