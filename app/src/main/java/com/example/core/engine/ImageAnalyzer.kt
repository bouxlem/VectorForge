package com.example.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.model.ImageCategory
import com.example.core.model.ImageFeatures
import kotlin.math.abs
import kotlin.math.min

/**
 * Stage 2: Image Feature Analysis & Category Classifier.
 */
object ImageAnalyzer {

    fun analyze(bitmap: Bitmap): ImageFeatures {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        // Downsample for rapid statistical sampling if huge
        val sampleStep = (min(width, height) / 256).coerceAtLeast(1)
        var sampledCount = 0

        var transparentCount = 0
        var softAlphaCount = 0
        val colorHistogram = HashMap<Int, Int>()

        var edgeEnergy = 0.0
        var noiseSum = 0.0

        val sampledW = width / sampleStep
        val sampledH = height / sampleStep
        val lumGrid = Array(sampledH) { DoubleArray(sampledW) }

        for (sy in 0 until sampledH) {
            val y = sy * sampleStep
            for (sx in 0 until sampledW) {
                val x = sx * sampleStep
                val pixel = bitmap.getPixel(x, y)
                val a = Color.alpha(pixel)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Alpha characteristics
                if (a < 12) {
                    transparentCount++
                } else if (a in 13..242) {
                    softAlphaCount++
                }

                // Color histogram with 5-bit color quantization for distinct color clusters
                val qColor = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
                colorHistogram[qColor] = (colorHistogram[qColor] ?: 0) + 1

                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                lumGrid[sy][sx] = lum
                sampledCount++
            }
        }

        // Edge density & local variance (Sobel filter on sampled grid)
        for (sy in 1 until sampledH - 1) {
            for (sx in 1 until sampledW - 1) {
                val gx = (lumGrid[sy - 1][sx + 1] + 2 * lumGrid[sy][sx + 1] + lumGrid[sy + 1][sx + 1]) -
                        (lumGrid[sy - 1][sx - 1] + 2 * lumGrid[sy][sx - 1] + lumGrid[sy + 1][sx - 1])
                val gy = (lumGrid[sy + 1][sx - 1] + 2 * lumGrid[sy + 1][sx] + lumGrid[sy + 1][sx + 1]) -
                        (lumGrid[sy - 1][sx - 1] + 2 * lumGrid[sy - 1][sx] + lumGrid[sy - 1][sx + 1])
                val mag = abs(gx) + abs(gy)
                if (mag > 40.0) {
                    edgeEnergy++
                }

                val center = lumGrid[sy][sx]
                val avgNeighbor = (lumGrid[sy - 1][sx] + lumGrid[sy + 1][sx] + lumGrid[sy][sx - 1] + lumGrid[sy][sx + 1]) / 4.0
                noiseSum += abs(center - avgNeighbor)
            }
        }

        val innerGridCount = ((sampledH - 2) * (sampledW - 2)).coerceAtLeast(1)
        val edgeDensity = (edgeEnergy / innerGridCount).coerceIn(0.0, 1.0)
        val noiseScore = (noiseSum / innerGridCount).coerceAtLeast(0.0)
        val distinctColors = colorHistogram.size
        val hasAlpha = (transparentCount + softAlphaCount) > (sampledCount * 0.01)
        val softAlphaRatio = if (sampledCount > 0) softAlphaCount.toDouble() / sampledCount else 0.0

        // Rule-based classification tree per AI_STRATEGY & VECTOR_ALGORITHMS §2
        val (category, confidence) = classify(
            edgeDensity = edgeDensity,
            distinctColors = distinctColors,
            hasAlpha = hasAlpha,
            softAlphaRatio = softAlphaRatio,
            noiseScore = noiseScore
        )

        return ImageFeatures(
            edgeDensity = edgeDensity,
            uniqueColors = distinctColors,
            hasAlpha = hasAlpha,
            softAlphaRatio = softAlphaRatio,
            noiseScore = noiseScore,
            detectedCategory = category,
            confidence = confidence
        )
    }

    private fun classify(
        edgeDensity: Double,
        distinctColors: Int,
        hasAlpha: Boolean,
        softAlphaRatio: Double,
        noiseScore: Double
    ): Pair<ImageCategory, Double> {
        return when {
            distinctColors <= 4 && edgeDensity < 0.15 -> {
                ImageCategory.ICON to 0.96
            }
            distinctColors in 3..24 && edgeDensity in 0.03..0.25 -> {
                if (hasAlpha) ImageCategory.STICKER to 0.92 else ImageCategory.LOGO to 0.94
            }
            distinctColors in 10..60 && edgeDensity in 0.10..0.35 -> {
                ImageCategory.FLAT_ILLUSTRATION to 0.90
            }
            distinctColors <= 8 && edgeDensity > 0.20 -> {
                ImageCategory.LINE_DRAWING to 0.88
            }
            distinctColors in 4..16 && edgeDensity > 0.25 -> {
                ImageCategory.TYPOGRAPHY to 0.89
            }
            distinctColors > 120 || noiseScore > 25.0 -> {
                ImageCategory.PHOTOGRAPH to 0.85
            }
            else -> {
                ImageCategory.COMPLEX_ART to 0.84
            }
        }
    }
}
