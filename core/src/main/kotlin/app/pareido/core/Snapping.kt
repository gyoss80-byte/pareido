package app.pareido.core

import kotlin.math.max

/**
 * "Do you see something?" mode: the user's finger stroke is never drawn as the
 * outline. Instead we keep only the pieces of real contours that run along it.
 */
object Snapping {

    data class Snap(
        /** Real contour pieces near the stroke (ids are the source contour ids). */
        val contours: List<Contour>,
        /** 0..1: how much of the user's stroke has a real edge nearby. */
        val strokeCoverage: Float,
    )

    /**
     * @param tolerance max distance in processing pixels between stroke and edge;
     *   defaults to 4% of the longest image side.
     */
    fun snap(stroke: List<Pt>, scan: EdgeScan, tolerance: Float = max(scan.width, scan.height) * 0.04f): Snap {
        if (stroke.size < 2 || scan.contours.isEmpty()) return Snap(emptyList(), 0f)

        val pieces = mutableListOf<Contour>()
        for (contour in scan.contours) {
            val dense = densify(contour.points, step = 2f)
            var current = mutableListOf<Pt>()
            for (p in dense) {
                if (Geometry.distanceToPolyline(p, stroke) <= tolerance) {
                    current.add(p)
                } else {
                    if (current.size >= 3) pieces.add(Contour(contour.id, current))
                    current = mutableListOf()
                }
            }
            if (current.size >= 3) pieces.add(Contour(contour.id, current))
        }
        val kept = pieces.filter { it.pixelLength >= tolerance * 0.5f }

        val denseStroke = densify(stroke, step = 3f)
        val covered = denseStroke.count { p -> kept.any { Geometry.distanceToPolyline(p, it.points) <= tolerance } }
        return Snap(kept, covered.toFloat() / denseStroke.size)
    }

    /** Adds points along each segment (still exactly on the original line) so distance tests are even. */
    internal fun densify(points: List<Pt>, step: Float): List<Pt> {
        if (points.size < 2) return points
        val out = mutableListOf(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val n = max(1, (len / step).toInt())
            for (k in 1..n) {
                val t = k.toFloat() / n
                out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }
}
