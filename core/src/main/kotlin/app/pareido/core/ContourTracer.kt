package app.pareido.core

import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns an edge map into polylines by walking from edge pixel to neighbouring
 * edge pixel. Each line only ever connects touching edge pixels, so no new
 * lines are invented.
 */
object ContourTracer {

    // 4-neighbours first so lines follow the edge as tightly as possible.
    private val DX = intArrayOf(1, 0, -1, 0, 1, -1, -1, 1)
    private val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)

    /**
     * @param maxContours keep only the longest lines (they are the ones worth numbering).
     * @param minLengthFraction drop lines shorter than this fraction of the image diagonal.
     */
    fun trace(
        map: EdgeDetector.EdgeMap,
        maxContours: Int = 60,
        minLengthFraction: Float = 0.03f,
        simplifyEpsilon: Float = 1.0f,
    ): List<Contour> {
        val w = map.width
        val h = map.height
        val visited = BooleanArray(w * h)
        val paths = mutableListOf<List<Pt>>()

        fun neighbourCount(x: Int, y: Int): Int {
            var n = 0
            for (k in 0 until 8) {
                val nx = x + DX[k]; val ny = y + DY[k]
                if (nx in 0 until w && ny in 0 until h && map.isEdge(nx, ny)) n++
            }
            return n
        }

        fun walk(startX: Int, startY: Int): MutableList<Pt> {
            val out = mutableListOf<Pt>()
            var x = startX
            var y = startY
            while (true) {
                var moved = false
                for (k in 0 until 8) {
                    val nx = x + DX[k]; val ny = y + DY[k]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val i = ny * w + nx
                    if (map.edges[i] && !visited[i]) {
                        visited[i] = true
                        out.add(Pt(nx.toFloat(), ny.toFloat()))
                        x = nx; y = ny
                        moved = true
                        break
                    }
                }
                if (!moved) return out
            }
        }

        fun traceFrom(x: Int, y: Int) {
            visited[y * w + x] = true
            val forward = walk(x, y)
            val backward = walk(x, y)
            val path = ArrayList<Pt>(forward.size + backward.size + 1)
            for (i in backward.indices.reversed()) path.add(backward[i])
            path.add(Pt(x.toFloat(), y.toFloat()))
            path.addAll(forward)
            paths.add(path)
        }

        // Start at line ends first so open lines come out in one piece, then closed loops.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (map.edges[i] && !visited[i] && neighbourCount(x, y) <= 1) traceFrom(x, y)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (map.edges[i] && !visited[i]) traceFrom(x, y)
        }

        val minLength = max(8f, hypot(w.toFloat(), h.toFloat()) * minLengthFraction)
        return paths
            .map { it to Geometry.polylineLength(it) }
            .filter { it.second >= minLength }
            .sortedByDescending { it.second }
            .take(maxContours)
            .mapIndexed { index, (points, _) ->
                Contour(id = index + 1, points = Geometry.simplify(points, simplifyEpsilon))
            }
    }
}

/** Convenience: photo -> numbered real edge lines. */
object EdgeScanner {
    const val PROCESSING_MAX_DIM = 640

    fun scan(image: PixelImage, sensitivity: Float, maxContours: Int = 60): EdgeScan {
        val small = ImageOps.downscale(image, PROCESSING_MAX_DIM)
        val map = EdgeDetector.detect(small, sensitivity)
        val contours = ContourTracer.trace(map, maxContours = maxContours)
        return EdgeScan(small.width, small.height, sensitivity, contours)
    }
}
