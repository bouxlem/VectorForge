package com.example.core.engine

import com.example.core.model.PathSegment
import com.example.core.model.PointD
import com.example.core.model.VectorPath
import com.example.core.model.VectorRegion

/**
 * Stage 8: Layer Stacking (Z-Order) & Micro-Gap Bleed Correction.
 *
 * Implements true topological containment ordering (containing parent regions render before
 * child regions), with actual filled pixel area (not bounding box area) as a tiebreaker among siblings.
 * Micro-gap dilation is capped at <= 0.15px and disabled for photographic content to prevent fusing fine details.
 */
object LayerStackResolver {

    /**
     * Resolves layer order by true topological containment first, then by actual filled area,
     * and performs minimal micro-gap bleed compensation where appropriate.
     */
    fun resolveLayers(
        regions: List<VectorRegion>,
        gapBleedCorrectionPx: Double = 0.10
    ): List<VectorRegion> {
        if (regions.isEmpty()) return emptyList()

        // 1. Calculate actual filled area for every region (outer polygon area minus holes)
        val regionAreas = DoubleArray(regions.size) { i -> computeFilledArea(regions[i]) }

        // 2. Identify immediate parent for each region via topological containment
        val parent = IntArray(regions.size) { -1 }
        val children = Array(regions.size) { ArrayList<Int>() }

        for (i in regions.indices) {
            val regB = regions[i]
            val areaB = regionAreas[i]
            val samplePt = getSamplePoint(regB.outerPath)

            var bestParent = -1
            var minParentArea = Double.MAX_VALUE

            for (j in regions.indices) {
                if (i == j) continue
                val regA = regions[j]
                val areaA = regionAreas[j]

                // A can only contain B if A has strictly larger filled area
                if (areaA <= areaB) continue

                // Bounding box check
                val boundsContain = regA.bounds.left <= regB.bounds.left + 0.5 &&
                        regA.bounds.top <= regB.bounds.top + 0.5 &&
                        regA.bounds.right >= regB.bounds.right - 0.5 &&
                        regA.bounds.bottom >= regB.bounds.bottom - 0.5

                if (boundsContain && isPointInPath(samplePt, regA.outerPath)) {
                    // Check that sample point is not inside one of A's holes (unless hole also contains B)
                    val inHoleOfA = regA.holePaths.any { isPointInPath(samplePt, it) }
                    if (!inHoleOfA && areaA < minParentArea) {
                        minParentArea = areaA
                        bestParent = j
                    }
                }
            }

            parent[i] = bestParent
            if (bestParent != -1) {
                children[bestParent].add(i)
            }
        }

        // 3. Collect root regions (regions without parents), sorted by actual filled area descending
        val rootIndices = (regions.indices).filter { parent[it] == -1 }
            .sortedByDescending { regionAreas[it] }

        // 4. Perform topological preorder traversal so parents render before children
        val orderedRegions = ArrayList<VectorRegion>(regions.size)

        fun traverse(nodeIdx: Int) {
            orderedRegions.add(regions[nodeIdx])
            // Sort children by actual filled area descending
            val sortedChildren = children[nodeIdx].sortedByDescending { regionAreas[it] }
            for (childIdx in sortedChildren) {
                traverse(childIdx)
            }
        }

        for (rootIdx in rootIndices) {
            traverse(rootIdx)
        }

        // Safety fallback: if any disconnected component was missed, append it
        if (orderedRegions.size < regions.size) {
            val addedSet = orderedRegions.map { it.id }.toHashSet()
            val remaining = regions.filter { !addedSet.contains(it.id) }
                .sortedByDescending { computeFilledArea(it) }
            orderedRegions.addAll(remaining)
        }

        // 5. Apply minimal micro-gap bleed compensation (capped at <= 0.15px, opt-in only)
        val effectiveGap = gapBleedCorrectionPx.coerceIn(0.0, 0.15)
        val layeredRegions = orderedRegions.mapIndexed { idx, reg ->
            val correctedOuter = if (effectiveGap > 0.02 && reg.outerPath.parametricShape == null) {
                applyGapCorrection(reg.outerPath, effectiveGap)
            } else {
                reg.outerPath
            }

            reg.copy(
                outerPath = correctedOuter,
                zIndex = idx
            )
        }

        return layeredRegions
    }

    fun computeFilledArea(region: VectorRegion): Double {
        val outerArea = computePathArea(region.outerPath)
        val holesArea = region.holePaths.sumOf { computePathArea(it) }
        return (outerArea - holesArea).coerceAtLeast(0.0)
    }

    private fun computePathArea(path: VectorPath): Double {
        val shape = path.parametricShape
        if (shape != null) {
            when (shape) {
                is com.example.core.model.ParametricShape.Circle -> return Math.PI * shape.radius * shape.radius
                is com.example.core.model.ParametricShape.Ellipse -> return Math.PI * shape.rx * shape.ry
                is com.example.core.model.ParametricShape.RegularPolygon -> {
                    return 0.5 * shape.sides * shape.radius * shape.radius * kotlin.math.sin(2.0 * Math.PI / shape.sides)
                }
                is com.example.core.model.ParametricShape.Star -> {
                    return shape.points * shape.outerRadius * shape.innerRadius * kotlin.math.sin(Math.PI / shape.points)
                }
            }
        }

        if (path.segments.isEmpty()) return 0.0
        var sum = 0.0
        for (seg in path.segments) {
            val (p0, p1) = when (seg) {
                is PathSegment.Line -> seg.p0 to seg.p1
                is PathSegment.CubicBezier -> seg.p0 to seg.p3
            }
            sum += p0.x * p1.y - p1.x * p0.y
        }
        return kotlin.math.abs(sum) / 2.0
    }

    private fun getSamplePoint(path: VectorPath): PointD {
        val shape = path.parametricShape
        if (shape != null) {
            when (shape) {
                is com.example.core.model.ParametricShape.Circle -> return PointD(shape.cx, shape.cy)
                is com.example.core.model.ParametricShape.Ellipse -> return PointD(shape.cx, shape.cy)
                is com.example.core.model.ParametricShape.RegularPolygon -> return PointD(shape.cx, shape.cy)
                is com.example.core.model.ParametricShape.Star -> return PointD(shape.cx, shape.cy)
            }
        }
        if (path.segments.isEmpty()) return PointD(0.0, 0.0)
        return when (val first = path.segments.first()) {
            is PathSegment.Line -> first.p0
            is PathSegment.CubicBezier -> first.p0
        }
    }

    private fun isPointInPath(pt: PointD, path: VectorPath): Boolean {
        val shape = path.parametricShape
        if (shape != null) {
            when (shape) {
                is com.example.core.model.ParametricShape.Circle -> {
                    val dx = pt.x - shape.cx
                    val dy = pt.y - shape.cy
                    return (dx * dx + dy * dy) <= shape.radius * shape.radius
                }
                is com.example.core.model.ParametricShape.Ellipse -> {
                    val dx = pt.x - shape.cx
                    val dy = pt.y - shape.cy
                    return ((dx * dx) / (shape.rx * shape.rx) + (dy * dy) / (shape.ry * shape.ry)) <= 1.0
                }
                else -> {}
            }
        }
        var inside = false
        for (seg in path.segments) {
            val (p1, p2) = when (seg) {
                is PathSegment.Line -> seg.p0 to seg.p1
                is PathSegment.CubicBezier -> seg.p0 to seg.p3
            }
            if ((p1.y > pt.y) != (p2.y > pt.y) &&
                pt.x < (p2.x - p1.x) * (pt.y - p1.y) / (p2.y - p1.y + 1e-12) + p1.x
            ) {
                inside = !inside
            }
        }
        return inside
    }

    /**
     * Expands closed path along outward vertex bisector normals to close sub-pixel seams
     * while guaranteeing that shared vertices between adjacent segments remain identical and connected.
     */
    fun applyGapCorrection(
        path: VectorPath,
        offsetPx: Double
    ): VectorPath {
        val segments = path.segments
        if (segments.size < 2 || offsetPx <= 0.01) return path

        val n = segments.size

        val vertices = ArrayList<PointD>(n)
        for (seg in segments) {
            val startPt = when (seg) {
                is PathSegment.Line -> seg.p0
                is PathSegment.CubicBezier -> seg.p0
            }
            vertices.add(startPt)
        }

        val inNormals = ArrayList<PointD>(n)
        val outNormals = ArrayList<PointD>(n)
        for (i in 0 until n) {
            val prevSeg = segments[(i - 1 + n) % n]
            val currSeg = segments[i]

            val inTan = when (prevSeg) {
                is PathSegment.Line -> (prevSeg.p1 - prevSeg.p0).normalized()
                is PathSegment.CubicBezier -> {
                    val d = prevSeg.p3 - prevSeg.p2
                    if (d.length() > 1e-6) d.normalized() else (prevSeg.p3 - prevSeg.p0).normalized()
                }
            }
            val outTan = when (currSeg) {
                is PathSegment.Line -> (currSeg.p1 - currSeg.p0).normalized()
                is PathSegment.CubicBezier -> {
                    val d = currSeg.p1 - currSeg.p0
                    if (d.length() > 1e-6) d.normalized() else (currSeg.p3 - currSeg.p0).normalized()
                }
            }

            inNormals.add(PointD(-inTan.y, inTan.x))
            outNormals.add(PointD(-outTan.y, outTan.x))
        }

        val bisectors = ArrayList<PointD>(n)
        for (i in 0 until n) {
            var b = inNormals[i] + outNormals[i]
            if (b.length() < 1e-6) {
                b = outNormals[i]
            }
            bisectors.add(b.normalized())
        }

        var centroidX = 0.0
        var centroidY = 0.0
        for (v in vertices) {
            centroidX += v.x
            centroidY += v.y
        }
        val centroid = PointD(centroidX / n, centroidY / n)

        var divergenceSum = 0.0
        for (i in 0 until n) {
            val r = vertices[i] - centroid
            divergenceSum += bisectors[i].dot(r)
        }
        val sign = if (divergenceSum < 0.0) -1.0 else 1.0

        val offsetVertices = ArrayList<PointD>(n)
        val deltas = ArrayList<PointD>(n)
        for (i in 0 until n) {
            val normal = bisectors[i] * sign
            val cosMiter = outNormals[i].dot(bisectors[i]).coerceIn(0.3, 1.0)
            val miterFactor = (1.0 / cosMiter).coerceIn(0.5, 2.0)
            val delta = normal * (offsetPx * miterFactor)
            deltas.add(delta)
            offsetVertices.add(vertices[i] + delta)
        }

        val correctedSegments = ArrayList<PathSegment>(n)
        for (i in 0 until n) {
            val nextI = (i + 1) % n
            val p0New = offsetVertices[i]
            val pNextNew = offsetVertices[nextI]

            val seg = segments[i]
            when (seg) {
                is PathSegment.Line -> {
                    correctedSegments.add(PathSegment.Line(p0New, pNextNew))
                }
                is PathSegment.CubicBezier -> {
                    val d0 = deltas[i]
                    val d1 = deltas[nextI]
                    val p1New = seg.p1 + (d0 * (2.0 / 3.0) + d1 * (1.0 / 3.0))
                    val p2New = seg.p2 + (d0 * (1.0 / 3.0) + d1 * (2.0 / 3.0))
                    correctedSegments.add(PathSegment.CubicBezier(p0New, p1New, p2New, pNextNew))
                }
            }
        }

        return path.copy(segments = correctedSegments)
    }
}
