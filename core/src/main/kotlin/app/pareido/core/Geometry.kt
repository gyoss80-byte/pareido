package app.pareido.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object Geometry {

    fun polylineLength(points: List<Pt>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    fun bounds(points: List<Pt>): Box {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return Box(minX, minY, maxX, maxY)
    }

    /** Distance from [p] to the segment [a]-[b]. */
    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    fun distanceToPolyline(p: Pt, line: List<Pt>): Float {
        if (line.isEmpty()) return Float.MAX_VALUE
        if (line.size == 1) return hypot(p.x - line[0].x, p.y - line[0].y)
        var best = Float.MAX_VALUE
        for (i in 1 until line.size) best = min(best, distanceToSegment(p, line[i - 1], line[i]))
        return best
    }

    /**
     * Ramer–Douglas–Peucker. Only drops points; every kept point is an original
     * edge pixel and the simplified line stays within [epsilon] px of the real edge.
     */
    fun simplify(points: List<Pt>, epsilon: Float): List<Pt> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast().let { it[0] to it[1] }
            var maxDist = 0f
            var index = -1
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i], points[start], points[end])
                if (d > maxDist) { maxDist = d; index = i }
            }
            if (index != -1 && maxDist > epsilon) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
