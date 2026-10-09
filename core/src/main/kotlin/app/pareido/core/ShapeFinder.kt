package app.pareido.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * No-API-key mode: groups real contours that touch or nearly touch into shapes and
 * ranks them by size. It can't say what a shape looks like; the user names it.
 */
object ShapeFinder {

    /**
     * @param gapFraction contours closer than this fraction of the longest image side join one shape.
     */
    fun candidates(scan: EdgeScan, maxShapes: Int = 6, gapFraction: Float = 0.025f): List<Figure> {
        val contours = scan.contours
        if (contours.isEmpty()) return emptyList()
        val gap = max(scan.width, scan.height) * gapFraction

        val parent = IntArray(contours.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }

        val boxes = contours.map { Geometry.bounds(it.points) }
        val samples = contours.map { Snapping.densify(it.points, step = gap / 2).filterIndexed { i, _ -> i % 2 == 0 } }
        for (i in contours.indices) for (j in i + 1 until contours.size) {
            val a = boxes[i]; val b = boxes[j]
            // Cheap reject: bounding boxes farther apart than the gap can't touch.
            if (a.minX - gap > b.maxX || b.minX - gap > a.maxX || a.minY - gap > b.maxY || b.minY - gap > a.maxY) continue
            if (samples[i].any { p -> Geometry.distanceToPolyline(p, contours[j].points) <= gap }) {
                parent[root(i)] = root(j)
            }
        }

        val groups = contours.indices.groupBy { root(it) }.values
        val scored = groups.map { members ->
            val length = members.sumOf { contours[it].pixelLength.toDouble() }.toFloat()
            val box = Geometry.bounds(members.flatMap { contours[it].points })
            val size = sqrt((box.maxX - box.minX) * (box.maxY - box.minY))
            members to length + size
        }.sortedByDescending { it.second }.take(maxShapes)

        val best = scored.firstOrNull()?.second ?: return emptyList()
        return scored.mapIndexed { index, (members, score) ->
            Figure(
                label = "Shape ${index + 1}",
                description = "",
                contourIds = members.map { contours[it].id }.sorted(),
                confidence = score / best,
                byClaude = false,
            )
        }
    }
}
