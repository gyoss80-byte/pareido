package app.pareido.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeMatcherTest {
    private val sky = 0xFF3A7BD5.toInt()
    private val cloud = 0xFFF2F2F2.toInt()

    /** Polygon (in shape units) placed at (cx, cy) with size and rotation. */
    private class Shape(val poly: List<Pair<Double, Double>>, val cx: Double, val cy: Double, val size: Double, val rotDeg: Double = 0.0)

    private fun inside(px: Double, py: Double, poly: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    private fun render(w: Int, h: Int, vararg shapes: Shape): PixelImage {
        val px = IntArray(w * h) { sky }
        for (s in shapes) {
            val r = Math.toRadians(s.rotDeg)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = (x - s.cx) / s.size; val dy = (y - s.cy) / s.size
                val ux = dx * cos(-r) - dy * sin(-r); val uy = dx * sin(-r) + dy * cos(-r)
                if (inside(ux, uy, s.poly)) px[y * w + x] = cloud
            }
        }
        return PixelImage(w, h, px)
    }

    private val star = (0 until 10).map { k ->
        val a = -Math.PI / 2 + k * Math.PI / 5
        val r = if (k % 2 == 0) 1.0 else 0.42
        r * cos(a) to r * sin(a)
    }
    private val circle = (0 until 40).map { k -> cos(k * Math.PI / 20) to sin(k * Math.PI / 20) }
    private val boot = listOf(-0.6 to -1.0, 0.0 to -1.0, 0.0 to 0.3, 0.9 to 0.3, 0.9 to 1.0, -0.6 to 1.0) // an L / boot

    private fun teach(label: String, shape: List<Pair<Double, Double>>): ShapeTemplate {
        val scan = EdgeScanner.scan(render(240, 240, Shape(shape, 120.0, 120.0, 80.0)), 0.5f)
        return ShapeMatcher.template(label, scan.contours)!!
    }

    @Test
    fun recognizesTaughtShapeAtNewSizeAndAngleButNotOthers() {
        val starT = teach("star", star)
        val circleT = teach("ball", circle)
        val bootT = teach("boot", boot)

        // New photo: a smaller, rotated star on the left and a boot on the right.
        val scan = EdgeScanner.scan(
            render(400, 240, Shape(star, 100.0, 120.0, 55.0, rotDeg = 20.0), Shape(boot, 290.0, 120.0, 70.0, rotDeg = -15.0)),
            0.5f,
        )
        val found = ShapeMatcher.findTaught(scan, listOf(starT, circleT, bootT))
        println("found: " + found.map { "${it.label}=${"%.2f".format(it.confidence)}" })
        val labels = found.map { it.label }.toSet()
        assertEquals(setOf("star", "boot"), labels)

        val starFig = found.first { it.label == "star" }
        val starPts = scan.contours.filter { it.id in starFig.contourIds }.flatMap { it.points }
        assertTrue(starPts.all { it.x < 200 }, "star outline should be the star on the left")
    }

    @Test
    fun differentShapesScoreLow() {
        val circleT = teach("ball", circle)
        val starScan = EdgeScanner.scan(render(240, 240, Shape(star, 120.0, 120.0, 80.0)), 0.5f)
        val s = ShapeMatcher.score(circleT, starScan.contours)
        println("circle vs star = $s")
        assertTrue(s < ShapeMatcher.MIN_SCORE)
    }

    @Test
    fun nothingTaughtFindsNothing() {
        val scan = EdgeScanner.scan(render(240, 240, Shape(star, 120.0, 120.0, 80.0)), 0.5f)
        assertTrue(ShapeMatcher.findTaught(scan, emptyList()).isEmpty())
    }
}
