package app.pareido.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A figure the user outlined and named, reduced to its shape. */
data class ShapeTemplate(val label: String, val points: List<Pt>)

/**
 * The app's memory of figures the user taught it. On a new photo it looks for groups of
 * real edges shaped like a taught figure. It only compares shapes; it can't recognize
 * anything it hasn't been shown.
 */
object ShapeMatcher {
    private const val SAMPLES = 64

    /** Below this score a match isn't shown (1 = identical shape). */
    const val MIN_SCORE = 0.6f

    /** Turns the real edge pieces of a user's outline into a reusable template. */
    fun template(label: String, contours: List<Contour>): ShapeTemplate? {
        val pts = sample(contours, SAMPLES)
        if (pts.size < 8) return null
        return ShapeTemplate(label, normalize(pts))
    }

    /** Shape similarity 0..1, tolerant to position, size, mirroring and ±40° rotation. */
    fun score(template: ShapeTemplate, contours: List<Contour>): Float {
        val pts = sample(contours, SAMPLES)
        if (pts.size < 8) return 0f
        val candidate = normalize(pts)
        var best = Float.MAX_VALUE
        for (mirror in listOf(false, true)) {
            val base = if (mirror) candidate.map { Pt(-it.x, it.y) } else candidate
            for (deg in -40..40 step 10) {
                val r = Math.toRadians(deg.toDouble())
                val c = cos(r).toFloat(); val s = sin(r).toFloat()
                val rotated = base.map { Pt(it.x * c - it.y * s, it.x * s + it.y * c) }
                best = min(best, (chamfer(template.points, rotated) + chamfer(rotated, template.points)) / 2)
            }
        }
        // Normalized shapes have RMS radius 1, so a mean gap of 0.35 is a poor fit.
        return (1f - best / 0.35f).coerceIn(0f, 1f)
    }

    /**
     * Searches the photo's real edges for taught figures. Returns at most one match per
     * label, strongest first; nothing at all if no candidate is a convincing fit.
     */
    fun findTaught(scan: EdgeScan, templates: List<ShapeTemplate>, maxResults: Int = 4): List<Figure> {
        if (templates.isEmpty() || scan.contours.isEmpty()) return emptyList()
        val byId = scan.contours.associateBy { it.id }
        val minLength = hypot(scan.width.toFloat(), scan.height.toFloat()) * 0.08f
        val candidates = listOf(0.015f, 0.03f, 0.06f)
            .flatMap { gap -> ShapeFinder.candidates(scan, maxShapes = 8, gapFraction = gap) }
            .map { it.contourIds }
            .distinct()
            .map { ids -> ids to ids.mapNotNull { byId[it] } }
            .filter { (_, cs) -> cs.sumOf { it.pixelLength.toDouble() } >= minLength }

        val best = mutableMapOf<String, Figure>()
        for (t in templates) {
            for ((ids, cs) in candidates) {
                val sc = score(t, cs)
                if (sc < MIN_SCORE) continue
                val key = t.label.lowercase()
                if ((best[key]?.confidence ?: 0f) < sc) {
                    best[key] = Figure(t.label, "Shaped like a ${t.label} you outlined before.", ids, sc, byClaude = false)
                }
            }
        }
        // Don't outline the same edges twice under different names.
        val used = mutableSetOf<Int>()
        return best.values.sortedByDescending { it.confidence }
            .filter { f -> f.contourIds.none { it in used }.also { if (it) used += f.contourIds } }
            .take(maxResults)
    }

    /** Points spread evenly along all the pieces (by length). */
    internal fun sample(contours: List<Contour>, n: Int): List<Pt> {
        val total = contours.sumOf { it.pixelLength.toDouble() }.toFloat()
        if (total <= 0f) return emptyList()
        val step = total / n
        val out = mutableListOf<Pt>()
        var carry = 0f
        for (c in contours) {
            for (i in 1 until c.points.size) {
                val a = c.points[i - 1]; val b = c.points[i]
                val len = hypot(b.x - a.x, b.y - a.y)
                var d = step - carry
                while (d <= len) {
                    val t = d / len
                    out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                    d += step
                }
                carry = len - (d - step)
            }
        }
        return out
    }

    /** Centre on the centroid and scale to RMS radius 1. */
    internal fun normalize(pts: List<Pt>): List<Pt> {
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        val rms = sqrt(pts.sumOf { ((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)).toDouble() } / pts.size).toFloat()
        val s = if (rms == 0f) 1f else rms
        return pts.map { Pt((it.x - cx) / s, (it.y - cy) / s) }
    }

    /** Mean distance from each point of [a] to its nearest point in [b]. */
    private fun chamfer(a: List<Pt>, b: List<Pt>): Float {
        var sum = 0f
        for (p in a) {
            var m = Float.MAX_VALUE
            for (q in b) m = min(m, (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y))
            sum += sqrt(max(0f, m))
        }
        return sum / a.size
    }
}
