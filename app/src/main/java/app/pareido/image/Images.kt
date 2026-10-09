package app.pareido.image

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import app.pareido.core.Contour
import app.pareido.core.EdgeScan
import app.pareido.core.PixelImage
import app.pareido.data.OutlineStyle
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Longest side of the photo we keep on disk. */
const val STORED_MAX_DIM = 1600

/** Longest side of images sent to Claude. */
const val CLAUDE_MAX_DIM = 1280

object Images {

    /** Copies a picked/taken photo into [dest] as an upright JPEG (EXIF rotation applied). */
    fun importPhoto(context: Context, uri: Uri, dest: File) {
        val bitmap = decode(ImageDecoder.createSource(context.contentResolver, uri), STORED_MAX_DIM)
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    fun importPhoto(src: File, dest: File) {
        val bitmap = decode(ImageDecoder.createSource(src), STORED_MAX_DIM)
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    fun load(file: File, maxDim: Int = STORED_MAX_DIM): Bitmap = decode(ImageDecoder.createSource(file), maxDim)

    private fun decode(source: ImageDecoder.Source, maxDim: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxDim) {
                val scale = maxDim.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }

    fun toPixelImage(bitmap: Bitmap): PixelImage {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(bitmap.width, bitmap.height, px)
    }

    fun scaled(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val s = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt(), true)
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** The photo for Claude: the untouched picture, just resized. */
    fun photoForClaude(photo: Bitmap): ByteArray = jpeg(scaled(photo, CLAUDE_MAX_DIM))

    /** The photo with every real contour drawn in its own colour and labelled with its number. */
    fun annotatedForClaude(photo: Bitmap, scan: EdgeScan): ByteArray {
        val base = scaled(photo, CLAUDE_MAX_DIM).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(base)
        val s = base.width.toFloat() / scan.width
        // Dim the photo a little so the coloured lines and numbers stand out.
        canvas.drawColor(Color.argb(70, 0, 0, 0))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(2f, base.width / 450f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val textSize = max(14f, base.width / 60f)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; this.textSize = textSize; isFakeBoldText = true }
        val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
        for (c in scan.contours) {
            val hue = (c.id * 47) % 360f
            line.color = Color.HSVToColor(floatArrayOf(hue, 0.9f, 1f))
            canvas.drawPath(pathOf(c, s, 0f, 0f), line)
        }
        for (c in scan.contours) {
            val mid = c.points[c.points.size / 2]
            val label = c.id.toString()
            val w = text.measureText(label)
            val x = (mid.x * s).coerceIn(w, base.width - w)
            val y = (mid.y * s).coerceIn(textSize, base.height - 4f)
            bubble.color = Color.HSVToColor(220, floatArrayOf((c.id * 47) % 360f, 0.35f, 1f))
            canvas.drawRoundRect(x - w / 2 - 4, y - textSize, x + w / 2 + 4, y + 5, 6f, 6f, bubble)
            canvas.drawText(label, x - w / 2, y, text)
        }
        return jpeg(base)
    }

    /** The photo with only the given real contour pieces highlighted (for "I see something"). */
    fun highlightedForClaude(photo: Bitmap, scan: EdgeScan, contours: List<Contour>): ByteArray {
        val base = scaled(photo, CLAUDE_MAX_DIM).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(base)
        OutlinePainter.draw(
            canvas, contours, scan, RectF(0f, 0f, base.width.toFloat(), base.height.toFloat()),
            OutlineStyle.SOLID, Color.rgb(255, 0, 200), max(3f, base.width / 300f),
        )
        return jpeg(base)
    }

    /** Photo + outline + a small caption, for sharing. The original photo file is never changed. */
    fun renderShareImage(
        photo: Bitmap, scan: EdgeScan, contours: List<Contour>, caption: String,
        style: OutlineStyle, color: Int, widthPx: Float,
        extra: List<Pair<List<Contour>, Int>> = emptyList(),
    ): Bitmap {
        val captionH = (photo.width / 14f).coerceAtLeast(48f)
        val out = Bitmap.createBitmap(photo.width, photo.height + captionH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(15, 27, 45))
        canvas.drawBitmap(photo, 0f, 0f, null)
        val rect = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        for ((cs, col) in extra) OutlinePainter.draw(canvas, cs, scan, rect, style, col, widthPx)
        OutlinePainter.draw(canvas, contours, scan, rect, style, color, widthPx)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = captionH * 0.45f }
        canvas.drawText(caption, captionH * 0.3f, photo.height + captionH * 0.65f, text)
        return out
    }

    fun share(context: Context, bitmap: Bitmap, text: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "pareido-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        shareFile(context, file, text)
    }

    fun shareFile(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun pathOf(c: Contour, scale: Float, dx: Float, dy: Float): Path = Path().apply {
        c.points.forEachIndexed { i, p ->
            val x = dx + p.x * scale
            val y = dy + p.y * scale
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

/**
 * Draws outlines. Styles only change how a line looks (colour, glow, dashes);
 * every line still runs exactly along the real contour points.
 */
object OutlinePainter {
    fun draw(canvas: Canvas, contours: List<Contour>, scan: EdgeScan, dst: RectF, style: OutlineStyle, color: Int, widthPx: Float) {
        if (contours.isEmpty()) return
        val scale = dst.width() / scan.width
        val paths = contours.map { Images.pathOf(it, scale, dst.left, dst.top) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        when (style) {
            OutlineStyle.SOLID -> {
                paint.color = Color.argb(140, 0, 0, 0); paint.strokeWidth = widthPx + 3f
                paths.forEach { canvas.drawPath(it, paint) }
                paint.color = color; paint.strokeWidth = widthPx
                paths.forEach { canvas.drawPath(it, paint) }
            }
            OutlineStyle.GLOW -> {
                // Soft halo from a few wide, faint strokes (works on hardware-accelerated canvases).
                for ((mult, alpha) in listOf(5f to 30, 3.5f to 50, 2.2f to 90)) {
                    paint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                    paint.strokeWidth = widthPx * mult
                    paths.forEach { canvas.drawPath(it, paint) }
                }
                paint.color = color; paint.strokeWidth = widthPx
                paths.forEach { canvas.drawPath(it, paint) }
                paint.color = Color.argb(200, 255, 255, 255); paint.strokeWidth = max(1f, widthPx * 0.35f)
                paths.forEach { canvas.drawPath(it, paint) }
            }
            OutlineStyle.CHALK -> {
                paint.color = Color.argb(225, Color.red(color), Color.green(color), Color.blue(color))
                paint.strokeWidth = widthPx
                paint.pathEffect = DashPathEffect(floatArrayOf(widthPx * 2.2f, widthPx * 1.4f), 0f)
                paths.forEach { canvas.drawPath(it, paint) }
            }
        }
    }

    /** Where an image of size [w]x[h] lands when fitted (letterboxed) into a box. */
    fun fitRect(w: Int, h: Int, boxW: Float, boxH: Float): RectF {
        val s = minOf(boxW / w, boxH / h)
        val dw = w * s
        val dh = h * s
        return RectF((boxW - dw) / 2, (boxH - dh) / 2, (boxW + dw) / 2, (boxH + dh) / 2)
    }
}
