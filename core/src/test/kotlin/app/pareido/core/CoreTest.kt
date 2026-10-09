package app.pareido.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreTest {

    private val sky = 0xFF3A7BD5.toInt()
    private val cloud = 0xFFF2F2F2.toInt()

    /** A white disc ("cloud") on blue sky. */
    private fun discImage(w: Int = 200, h: Int = 150, r: Float = 45f): PixelImage {
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (hypot(x - w / 2f, y - h / 2f) <= r) cloud else sky
        }
        return PixelImage(w, h, px)
    }

    @Test
    fun flatSkyHasNoEdges() {
        val scan = EdgeScanner.scan(PixelImage(120, 80, IntArray(120 * 80) { sky }), 1f)
        assertTrue(scan.contours.isEmpty())
    }

    @Test
    fun discOutlineFollowsTheRealBoundary() {
        val scan = EdgeScanner.scan(discImage(), 0.5f)
        assertTrue(scan.contours.isNotEmpty(), "expected the disc edge")
        val longest = scan.contours.first()
        assertEquals(1, longest.id)
        // Every point must sit on the real boundary (radius 45), within blur + 1px simplification.
        for (p in longest.points) {
            val d = hypot(p.x - 100f, p.y - 75f)
            assertTrue(d in 41f..49f, "point $p is ${d}px from centre, off the real edge")
        }
        assertTrue(longest.pixelLength > 2 * Math.PI * 45 * 0.8, "outline should go most of the way round")
    }

    @Test
    fun downscaleKeepsAspect() {
        val small = ImageOps.downscale(PixelImage(1000, 500, IntArray(500_000) { sky }), 100)
        assertEquals(100, small.width)
        assertEquals(50, small.height)
    }

    @Test
    fun simplifyOnlyDropsPoints() {
        val line = (0..20).map { Pt(it.toFloat(), 0f) }
        val s = Geometry.simplify(line, 1f)
        assertEquals(listOf(Pt(0f, 0f), Pt(20f, 0f)), s)
        assertTrue(s.all { it in line })
    }

    @Test
    fun snappingKeepsOnlyRealEdgesNearTheStroke() {
        val scan = EdgeScanner.scan(discImage(), 0.5f)
        // Trace roughly along the top half of the disc, a little off.
        val stroke = (0..30).map { k ->
            val a = Math.PI + Math.PI * k / 30
            Pt((100 + 49 * Math.cos(a)).toFloat(), (75 + 49 * Math.sin(a)).toFloat())
        }
        val snap = Snapping.snap(stroke, scan)
        assertTrue(snap.contours.isNotEmpty())
        assertTrue(snap.strokeCoverage > 0.8f, "coverage ${snap.strokeCoverage}")
        // Snapped pieces come from the real edge (top half), not the user's stroke.
        for (c in snap.contours) for (p in c.points) {
            assertTrue(hypot(p.x - 100f, p.y - 75f) in 41f..49f)
            assertTrue(p.y <= 75f + 8f, "bottom-half edge $p should not be included")
        }

        val farAway = listOf(Pt(5f, 5f), Pt(20f, 5f))
        assertEquals(0f, Snapping.snap(farAway, scan).strokeCoverage)
    }

    @Test
    fun streaks() {
        val today = LocalDate.of(2026, 10, 9)
        val days = listOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(10), today.minusDays(11), today.minusDays(12), today.minusDays(13))
        assertEquals(Streak(current = 3, best = 4, spottedToday = true), Streaks.compute(days, today))
        assertEquals(Streak(2, 4, false), Streaks.compute(days.drop(1), today))
        assertEquals(Streak(0, 1, false), Streaks.compute(listOf(today.minusDays(5)), today))
        assertEquals(Streak(0, 0, false), Streaks.compute(emptyList(), today))
    }

    @Test
    fun spending() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        val records = listOf(
            UsageRecord(now.toEpochMilli(), TokenUsage(Models.OPUS, 1_000_000, 100_000)), // 4 + 2 = 6
            UsageRecord(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), TokenUsage(Models.HAIKU, 1_000_000, 0)), // 0.10
        )
        val s = Spending.summarize(records, now, ZoneOffset.UTC)
        assertEquals(2, s.analyses)
        assertEquals(6.10, s.totalUsd, 1e-9)
        assertEquals(1, s.thisMonthAnalyses)
        assertEquals(6.0, s.thisMonthUsd, 1e-9)
    }

    @Test
    fun analyzerDropsInventedContourIdsAndWeakFigures() {
        val scan = EdgeScanner.scan(discImage(), 0.5f)
        val realId = scan.contours.first().id
        var sent: ClaudeRequest? = null
        val fake = ClaudeTransport { req ->
            sent = req
            ClaudeReply(
                """{"figures":[
                    {"label":"moon","description":"round","contour_ids":[$realId, 999],"confidence":0.8},
                    {"label":"ghost","description":"made up","contour_ids":[999],"confidence":0.9},
                    {"label":"blob","description":"meh","contour_ids":[$realId],"confidence":0.1}
                ],"comment":"nice sky"}""",
                TokenUsage(Models.OPUS, 10, 20),
            )
        }
        val result = PareidoAnalyzer(fake).findFigures(byteArrayOf(1), byteArrayOf(2), scan, exclude = listOf("sun"))
        assertEquals(listOf(Figure("moon", "round", listOf(realId), 0.8f)), result.figures)
        assertEquals("nice sky", result.comment)
        assertTrue(sent!!.text.contains("#$realId"))
        assertTrue(sent!!.text.contains("sun"))
        assertEquals(2, sent!!.jpegImages.size)
    }

    @Test
    fun analyzerRejectsUnreadableAnswers() {
        val scan = EdgeScanner.scan(discImage(), 0.5f)
        val fake = ClaudeTransport { ClaudeReply("not json", TokenUsage(Models.OPUS, 1, 1)) }
        val e = assertFailsWith<ClaudeException> { PareidoAnalyzer(fake).findFigures(byteArrayOf(), byteArrayOf(), scan) }
        assertEquals(ClaudeException.Kind.BAD_RESPONSE, e.kind)
    }

    @Test
    fun identifyIgnoresMatchWhenNoGuess() {
        val fake = ClaudeTransport {
            ClaudeReply("""{"label":"dog","description":"d","matches_guess":"yes","confidence":0.7,"comment":"c"}""", TokenUsage(Models.OPUS, 1, 1))
        }
        val r = PareidoAnalyzer(fake).identifyTraced(byteArrayOf(), byteArrayOf(), guess = " ")
        assertEquals("dog", r.label)
        assertEquals("no_guess", r.matchesGuess)
    }

    @Test
    fun sdkParamsBuild() {
        val req = ClaudeRequest(Models.OPUS, "sys", listOf(byteArrayOf(1, 2, 3)), "hi", Prompts.FIGURES_SCHEMA)
        val params = AnthropicTransport.buildParams(req)
        assertEquals("default", params._additionalBodyProperties()["fallbacks"]?.toString()?.trim('"'))
        val haiku = AnthropicTransport.buildParams(req.copy(model = Models.HAIKU))
        assertTrue("fallbacks" !in haiku._additionalBodyProperties())
        assertTrue(params.toString().contains("additionalProperties"))
    }
}

class ShapeFinderTest {
    @Test
    fun groupsTouchingEdgesAndRanksBySize() {
        val w = 300; val h = 200
        val sky = 0xFF3A7BD5.toInt(); val cloud = 0xFFF2F2F2.toInt()
        // A big disc and a small separate square.
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                kotlin.math.hypot(x - 90f, y - 100f) <= 60f -> cloud
                x in 220..250 && y in 40..70 -> cloud
                else -> sky
            }
        }
        val scan = EdgeScanner.scan(PixelImage(w, h, px), 0.5f)
        val shapes = ShapeFinder.candidates(scan)
        assertTrue(shapes.size >= 2, "expected two shapes, got $shapes")
        val first = scan.contours.filter { it.id in shapes[0].contourIds }.flatMap { it.points }
        assertTrue(first.all { it.x < 160 }, "biggest shape should be the disc")
        assertTrue(shapes.none { it.byClaude })
        val allIds = shapes.flatMap { it.contourIds }
        assertEquals(allIds.size, allIds.toSet().size, "a contour belongs to one shape")
        assertTrue(ShapeFinder.candidates(EdgeScan(10, 10, 0.5f, emptyList())).isEmpty())
    }
}

class NoisyPhotoTest {
    @Test
    fun noiseDoesNotDrownTheRealOutline() {
        val w = 320; val h = 240
        val rnd = java.util.Random(7)
        // A soft-edged cloud on sky, with camera-like noise on every pixel.
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val d = kotlin.math.hypot(x - 160f, y - 120f)
            val t = ((70f - d) / 6f).coerceIn(0f, 1f)
            fun ch(sky: Int, cloud: Int) = (sky + (cloud - sky) * t + rnd.nextGaussian() * 6).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(58, 240) shl 16) or (ch(123, 240) shl 8) or ch(213, 242)
        }
        val scan = EdgeScanner.scan(PixelImage(w, h, px), 0.5f)
        assertTrue(scan.contours.isNotEmpty())
        val longest = scan.contours.first()
        assertTrue(longest.pixelLength > 2 * Math.PI * 70 * 0.6, "outline too short: ${longest.pixelLength}")
        for (p in longest.points) {
            val d = kotlin.math.hypot(p.x - 160f, p.y - 120f)
            assertTrue(d in 60f..80f, "point $p is ${d}px from centre, off the real edge")
        }
        val noise = scan.contours.drop(1).sumOf { it.pixelLength.toDouble() }
        assertTrue(noise < longest.pixelLength, "too many noise lines: $noise px")
    }
}
