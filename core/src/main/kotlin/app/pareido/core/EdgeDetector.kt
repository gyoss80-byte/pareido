package app.pareido.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Canny-style edge detection. It only measures the photo: the output marks
 * pixels where brightness or colour really changes, and nothing is added.
 *
 * Two channels are combined so cloud/sky boundaries show up even when the
 * brightness is similar: luminance and colour saturation (blue sky is
 * saturated, cloud is not).
 */
object EdgeDetector {

    /** Edge map: `true` where a thin edge pixel was found. */
    class EdgeMap(val width: Int, val height: Int, val edges: BooleanArray) {
        fun isEdge(x: Int, y: Int) = edges[y * width + x]
        val count: Int get() = edges.count { it }
    }

    /** Below this gradient (0..255 scale) nothing counts as an edge, so flat sky stays empty. */
    private const val MIN_STRONG_GRADIENT = 8f

    /**
     * @param sensitivity 0 = only the boldest edges, 1 = faint detail too.
     */
    fun detect(image: PixelImage, sensitivity: Float): EdgeMap {
        val s = sensitivity.coerceIn(0f, 1f)
        val w = image.width
        val h = image.height
        val lum = FloatArray(w * h)
        val sat = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = image.argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat[i] = if (mx == 0) 0f else (mx - mn) * 255f / mx
        }

        // Less sensitivity = more blur = only large-scale shapes survive.
        val sigma = 1.2f + (1f - s) * 1.8f
        val lumBlur = gaussianBlur(lum, w, h, sigma)
        val satBlur = gaussianBlur(sat, w, h, sigma)

        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val (lgx, lgy) = sobel(lumBlur, w, x, y)
                val (sgx, sgy) = sobel(satBlur, w, x, y)
                val lm = sqrt(lgx * lgx + lgy * lgy)
                val sm = sqrt(sgx * sgx + sgy * sgy) * 0.8f
                val (gx, gy, m) = if (lm >= sm) Triple(lgx, lgy, lm) else Triple(sgx, sgy, sm)
                mag[i] = m / 4f // Sobel on 0..255 data peaks around 4*255
                dir[i] = quantizeDirection(gx, gy)
            }
        }

        val thin = nonMaxSuppression(mag, dir, w, h)

        // Thresholds adapt to the photo: only the strongest few percent of all pixels can seed an
        // edge, so busy textures don't turn into noise. On clean photos the floor decides instead.
        val floor = MIN_STRONG_GRADIENT + (1f - s) * 12f
        val high = max(floor, percentile(mag, 0.96f - 0.10f * s))
        val low = high * 0.45f
        return EdgeMap(w, h, hysteresis(thin, w, h, low, high))
    }

    private fun sobel(a: FloatArray, w: Int, x: Int, y: Int): Pair<Float, Float> {
        val i = y * w + x
        val tl = a[i - w - 1]; val t = a[i - w]; val tr = a[i - w + 1]
        val l = a[i - 1]; val r = a[i + 1]
        val bl = a[i + w - 1]; val b = a[i + w]; val br = a[i + w + 1]
        val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
        val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
        return gx to gy
    }

    /** 0 = horizontal gradient, 1 = 45°, 2 = vertical, 3 = 135°. */
    private fun quantizeDirection(gx: Float, gy: Float): Byte {
        val ax = abs(gx)
        val ay = abs(gy)
        return when {
            ay <= ax * 0.4142f -> 0
            ay >= ax * 2.4142f -> 2
            (gx > 0) == (gy > 0) -> 1
            else -> 3
        }.toByte()
    }

    private fun nonMaxSuppression(mag: FloatArray, dir: ByteArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m == 0f) continue
                val (a, b) = when (dir[i].toInt()) {
                    0 -> mag[i - 1] to mag[i + 1]
                    2 -> mag[i - w] to mag[i + w]
                    1 -> mag[i - w - 1] to mag[i + w + 1]
                    else -> mag[i - w + 1] to mag[i + w - 1]
                }
                if (m >= a && m >= b) out[i] = m
            }
        }
        return out
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return Float.MAX_VALUE
        val sorted = values.copyOf()
        sorted.sort()
        val idx = ((sorted.size - 1) * p.coerceIn(0f, 1f)).roundToInt()
        return sorted[idx]
    }

    private fun hysteresis(thin: FloatArray, w: Int, h: Int, low: Float, high: Float): BooleanArray {
        val edges = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        for (i in thin.indices) {
            if (thin[i] >= high && !edges[i]) {
                edges[i] = true
                stack[top++] = i
                while (top > 0) {
                    val j = stack[--top]
                    val jx = j % w
                    val jy = j / w
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = jx + dx
                        val ny = jy + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val k = ny * w + nx
                        if (!edges[k] && thin[k] >= low) {
                            edges[k] = true
                            stack[top++] = k
                        }
                    }
                }
            }
        }
        return edges
    }

    internal fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val radius = max(1, (sigma * 2.5f).roundToInt())
        val kernel = FloatArray(radius * 2 + 1) { k ->
            val d = (k - radius).toFloat()
            exp(-(d * d) / (2 * sigma * sigma))
        }
        val sum = kernel.sum()
        for (k in kernel.indices) kernel[k] /= sum

        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - radius).coerceIn(0, w - 1)
                    acc += src[row + sx] * kernel[k]
                }
                tmp[row + x] = acc
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - radius).coerceIn(0, h - 1)
                    acc += tmp[sy * w + x] * kernel[k]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }
}

object ImageOps {
    /** Box-filter downscale so the longest side is at most [maxDim]. Returns the same image if already small. */
    fun downscale(image: PixelImage, maxDim: Int): PixelImage {
        val longest = max(image.width, image.height)
        if (longest <= maxDim) return image
        val scale = maxDim.toFloat() / longest
        val nw = max(1, (image.width * scale).roundToInt())
        val nh = max(1, (image.height * scale).roundToInt())
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = y * image.height / nh
            val y1 = max(y0 + 1, (y + 1) * image.height / nh)
            for (x in 0 until nw) {
                val x0 = x * image.width / nw
                val x1 = max(x0 + 1, (x + 1) * image.width / nw)
                var r = 0; var g = 0; var b = 0; var n = 0
                for (sy in y0 until y1) for (sx in x0 until x1) {
                    val c = image.argb[sy * image.width + sx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                out[y * nw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return PixelImage(nw, nh, out)
    }
}
