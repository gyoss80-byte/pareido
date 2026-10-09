package app.pareido.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.pareido.core.Contour
import app.pareido.core.EdgeScan
import app.pareido.core.Pt
import app.pareido.data.OutlineStyle
import app.pareido.image.OutlinePainter

/** One set of real contours to draw over the photo. */
data class OutlineLayer(val contours: List<Contour>, val color: Int, val style: OutlineStyle, val widthDp: Float)

/**
 * The photo, untouched, with outline layers drawn on top. When [onStroke] is set, the user can
 * draw with a finger; strokes are reported in the scan's pixel coordinates.
 */
@Composable
fun PhotoCanvas(
    photo: Bitmap,
    scan: EdgeScan?,
    layers: List<OutlineLayer>,
    modifier: Modifier = Modifier,
    showAllEdges: Boolean = false,
    strokes: List<List<Pt>> = emptyList(),
    onStrokeStart: ((Pt) -> Unit)? = null,
    onStrokeMove: ((Pt) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    Box(modifier) {
        Image(photo.asImageBitmap(), contentDescription = "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (scan == null) return@Box

        val drawModifier = if (onStrokeStart != null && onStrokeMove != null) {
            Modifier.pointerInput(scan) {
                fun toScan(o: Offset): Pt {
                    val r = OutlinePainter.fitRect(photo.width, photo.height, size.width.toFloat(), size.height.toFloat())
                    val s = r.width() / scan.width
                    return Pt(((o.x - r.left) / s).coerceIn(0f, scan.width.toFloat()), ((o.y - r.top) / s).coerceIn(0f, scan.height.toFloat()))
                }
                detectDragGestures(
                    onDragStart = { onStrokeStart(toScan(it)) },
                    onDrag = { change, _ -> onStrokeMove(toScan(change.position)) },
                )
            }
        } else Modifier

        Canvas(Modifier.fillMaxSize().then(drawModifier)) {
            val rect = OutlinePainter.fitRect(photo.width, photo.height, size.width, size.height)
            val s = rect.width() / scan.width
            drawIntoCanvas { canvas ->
                if (showAllEdges) {
                    OutlinePainter.draw(canvas.nativeCanvas, scan.contours, scan, rect, OutlineStyle.SOLID, 0x99FFFFFF.toInt(), 1.2f * density)
                }
                for (layer in layers) {
                    OutlinePainter.draw(canvas.nativeCanvas, layer.contours, scan, rect, layer.style, layer.color, layer.widthDp * density)
                }
            }
            // The user's finger strokes: shown thin and dashed-looking white so they're clearly
            // "your guide", not the outline. The outline comes from real edges.
            for (stroke in strokes) {
                if (stroke.size < 2) continue
                val path = Path().apply {
                    stroke.forEachIndexed { i, p ->
                        val x = rect.left + p.x * s
                        val y = rect.top + p.y * s
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(width = 2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
