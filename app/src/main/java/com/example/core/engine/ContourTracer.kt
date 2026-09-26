package com.example.core.engine

import com.example.core.model.Contour
import com.example.core.model.PointD
import com.example.core.model.RectD
import com.example.core.model.SubpixelPoint
import kotlin.math.abs

/**
 * Stage 4: Contour Extraction & Hole Hierarchy.
 * Uses directed dual-grid edge decomposition to extract 100% closed outer boundaries
 * and internal holes with strict topological parent association.
 */
object ContourTracer {

    private data class RawContour(
        val points: List<PointD>,
        val isHole: Boolean,
        val area: Double,
        val bounds: RectD
    )

    private fun pointKey(x: Int, y: Int): Long {
        return (x.toLong() and 0xFFFFFFFFL) or (y.toLong() shl 32)
    }

    private fun keyX(k: Long): Int = k.toInt()
    private fun keyY(k: Long): Int = (k ushr 32).toInt()

    fun traceContours(
        mask: BooleanArray,
        width: Int,
        height: Int,
        minArea: Double = 2.0
    ): List<Contour> {
        val outgoing = HashMap<Long, ArrayList<Long>>()

        fun addEdge(x1: Int, y1: Int, x2: Int, y2: Int) {
            val u = pointKey(x1, y1)
            val v = pointKey(x2, y2)
            outgoing.getOrPut(u) { ArrayList(2) }.add(v)
        }

        // Build directed edges along all pixel boundaries
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    // North edge: (x, y) -> (x + 1, y)
                    if (y == 0 || !mask[(y - 1) * width + x]) {
                        addEdge(x, y, x + 1, y)
                    }
                    // East edge: (x + 1, y) -> (x + 1, y + 1)
                    if (x == width - 1 || !mask[y * width + (x + 1)]) {
                        addEdge(x + 1, y, x + 1, y + 1)
                    }
                    // South edge: (x + 1, y + 1) -> (x, y + 1)
                    if (y == height - 1 || !mask[(y + 1) * width + x]) {
                        addEdge(x + 1, y + 1, x, y + 1)
                    }
                    // West edge: (x, y + 1) -> (x, y)
                    if (x == 0 || !mask[y * width + (x - 1)]) {
                        addEdge(x, y + 1, x, y)
                    }
                }
            }
        }

        // Decompose directed edges into closed loops
        val rawContours = ArrayList<RawContour>()
        val startKeys = ArrayList(outgoing.keys)

        for (startKey in startKeys) {
            while (true) {
                val nextList = outgoing[startKey] ?: break
                if (nextList.isEmpty()) {
                    outgoing.remove(startKey)
                    break
                }

                // Trace loop starting from startKey
                val loop = ArrayList<PointD>()
                var curr = startKey
                var prevX = keyX(startKey)
                var prevY = keyY(startKey)

                while (true) {
                    val neighbors = outgoing[curr]
                    if (neighbors == null || neighbors.isEmpty()) break

                    // Pick neighbor with preference for sharpest right turn at saddle points
                    val chosenIdx: Int
                    if (neighbors.size == 1) {
                        chosenIdx = 0
                    } else {
                        val inDx = keyX(curr) - prevX
                        val inDy = keyY(curr) - prevY
                        var bestTurn = -10.0
                        var bestI = 0
                        for (i in neighbors.indices) {
                            val v = neighbors[i]
                            val outDx = keyX(v) - keyX(curr)
                            val outDy = keyY(v) - keyY(curr)
                            // Cross product (in x out) in screen coordinates: positive means turning right
                            val cross = inDx * outDy - inDy * outDx
                            val dot = inDx * outDx + inDy * outDy
                            val turnScore = cross * 10.0 + dot
                            if (turnScore > bestTurn) {
                                bestTurn = turnScore.toDouble()
                                bestI = i
                            }
                        }
                        chosenIdx = bestI
                    }

                    val nextKey = neighbors.removeAt(chosenIdx)
                    if (neighbors.isEmpty()) {
                        outgoing.remove(curr)
                    }

                    val cx = keyX(curr).toDouble()
                    val cy = keyY(curr).toDouble()
                    loop.add(PointD(cx, cy))

                    prevX = keyX(curr)
                    prevY = keyY(curr)
                    curr = nextKey

                    if (curr == startKey) {
                        break // Loop closed successfully!
                    }
                }

                if (loop.size >= 3) {
                    processLoop(loop, minArea)?.let { rawContours.add(it) }
                }
            }
        }

        // Establish parent-child hole hierarchy with 2D spatial grid index (O(N) scaling)
        val outerRawIndices = ArrayList<Int>()
        for (k in rawContours.indices) {
            if (!rawContours[k].isHole) {
                outerRawIndices.add(k)
            }
        }

        val gridCols = 16
        val gridRows = 16
        val cellW = (width.toDouble() / gridCols).coerceAtLeast(1.0)
        val cellH = (height.toDouble() / gridRows).coerceAtLeast(1.0)
        val spatialIndex = Array(gridRows * gridCols) { ArrayList<Int>() }

        for (outerIdx in outerRawIndices.indices) {
            val candidate = rawContours[outerRawIndices[outerIdx]]
            val minX = (candidate.bounds.left / cellW).toInt().coerceIn(0, gridCols - 1)
            val maxX = (candidate.bounds.right / cellW).toInt().coerceIn(0, gridCols - 1)
            val minY = (candidate.bounds.top / cellH).toInt().coerceIn(0, gridRows - 1)
            val maxY = (candidate.bounds.bottom / cellH).toInt().coerceIn(0, gridRows - 1)
            for (gy in minY..maxY) {
                val rowOffset = gy * gridCols
                for (gx in minX..maxX) {
                    spatialIndex[rowOffset + gx].add(outerIdx)
                }
            }
        }

        val results = ArrayList<Contour>()
        for (i in rawContours.indices) {
            val c = rawContours[i]
            var parentOuterIdx = -1
            if (c.isHole) {
                var minParentArea = Double.MAX_VALUE
                val holeCentroid = PointD(c.points.map { it.x }.average(), c.points.map { it.y }.average())

                val minX = (c.bounds.left / cellW).toInt().coerceIn(0, gridCols - 1)
                val maxX = (c.bounds.right / cellW).toInt().coerceIn(0, gridCols - 1)
                val minY = (c.bounds.top / cellH).toInt().coerceIn(0, gridRows - 1)
                val maxY = (c.bounds.bottom / cellH).toInt().coerceIn(0, gridRows - 1)

                val candidateOuterIndices = HashSet<Int>()
                for (gy in minY..maxY) {
                    val rowOffset = gy * gridCols
                    for (gx in minX..maxX) {
                        candidateOuterIndices.addAll(spatialIndex[rowOffset + gx])
                    }
                }

                for (outerIdx in candidateOuterIndices) {
                    val rawJ = outerRawIndices[outerIdx]
                    val candidate = rawContours[rawJ]
                    if (candidate.area > c.area) {
                        val boundsContain = candidate.bounds.left <= c.bounds.left + 0.5 &&
                                candidate.bounds.top <= c.bounds.top + 0.5 &&
                                candidate.bounds.right >= c.bounds.right - 0.5 &&
                                candidate.bounds.bottom >= c.bounds.bottom - 0.5

                        if (boundsContain) {
                            if (isPointInsidePolygon(holeCentroid, candidate.points) ||
                                c.points.any { isPointInsidePolygon(it, candidate.points) }) {
                                if (candidate.area < minParentArea) {
                                    minParentArea = candidate.area
                                    parentOuterIdx = outerIdx
                                }
                            }
                        }
                    }
                }
            }

            val subpixelPts = c.points.mapIndexed { pIdx, pt ->
                val prev = c.points[(pIdx - 1 + c.points.size) % c.points.size]
                val next = c.points[(pIdx + 1) % c.points.size]
                val tangent = (next - prev).normalized()
                val normal = PointD(-tangent.y, tangent.x)
                SubpixelPoint(point = pt, normal = normal, confidence = 1.0)
            }

            results.add(
                Contour(
                    points = subpixelPts,
                    isHole = c.isHole,
                    parentIndex = parentOuterIdx
                )
            )
        }

        return results
    }

    private fun processLoop(pts: List<PointD>, minArea: Double): RawContour? {
        val signedArea = computeSignedArea(pts)
        val area = abs(signedArea)
        if (area < minArea) return null

        // In standard screen coordinates (y-down):
        // Outer boundaries (clockwise) have positive area with Green's theorem sum (x_i y_{i+1} - x_{i+1} y_i)
        // Holes (counter-clockwise) have negative area.
        val isHole = signedArea < 0

        // Simplify consecutive collinear points along grid lines
        val cleaned = ArrayList<PointD>()
        val n = pts.size
        for (i in 0 until n) {
            val prev = pts[(i - 1 + n) % n]
            val curr = pts[i]
            val next = pts[(i + 1) % n]

            val d1 = curr - prev
            val d2 = next - curr
            val cross = d1.x * d2.y - d1.y * d2.x
            if (abs(cross) > 1e-6 || d1.dot(d2) < 0) {
                cleaned.add(curr)
            }
        }
        if (cleaned.size < 3) return null

        return RawContour(
            points = cleaned,
            isHole = isHole,
            area = area,
            bounds = Contour.calculateBounds(cleaned.map { SubpixelPoint(it) })
        )
    }

    private fun computeSignedArea(pts: List<PointD>): Double {
        var sum = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val curr = pts[i]
            val next = pts[(i + 1) % n]
            sum += curr.x * next.y - next.x * curr.y
        }
        return sum / 2.0
    }

    private fun isPointInsidePolygon(point: PointD, poly: List<PointD>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y + 1e-12) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
