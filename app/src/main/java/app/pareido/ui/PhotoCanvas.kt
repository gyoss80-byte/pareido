package app.pareido.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.pareido.core.Contour
import app.pareido.core.EdgeScan
import app.pareido.core.Pt
import app.pareido.data.OutlineStyle
import app.pareido.image.OutlinePainter

/** One set of real contours to draw over the photo. */
data class OutlineLayer(val contours: List<Contour>, val color: Int, val style: OutlineStyle, val widthDp: Float)

private const val MAX_ZOOM = 8f

/**
 * The photo, untouched, with outline layers drawn on top.
 * Pinch with two fingers to zoom and move; when zoomed in, one finger pans (or draws, in drawing mode).
 * Strokes are reported in the scan's pixel coordinates.
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
    onStrokeCancel: (() -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }

    // Gesture code runs in a long-lived coroutine, so read the latest callbacks through these.
    val start by rememberUpdatedState(onStrokeStart)
    val move by rememberUpdatedState(onStrokeMove)
    val cancel by rememberUpdatedState(onStrokeCancel)
    val currentScan by rememberUpdatedState(scan)

    Box(
        modifier
            .clipToBounds()
            .pointerInput(photo) {
                fun clampPan(p: Offset, z: Float): Offset {
                    val maxX = (z - 1f) * size.width / 2f
                    val maxY = (z - 1f) * size.height / 2f
                    return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                }
                /** Screen position -> scan pixel, undoing the zoom/pan. */
                fun toScan(screen: Offset): Pt? {
                    val s = currentScan ?: return null
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val content = c + (screen - c - pan) / zoom
                    val r = OutlinePainter.fitRect(photo.width, photo.height, size.width.toFloat(), size.height.toFloat())
                    val k = r.width() / s.width
                    return Pt(((content.x - r.left) / k).coerceIn(0f, s.width.toFloat()), ((content.y - r.top) / k).coerceIn(0f, s.height.toFloat()))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drawing = start != null && move != null
                    var strokeStarted = false
                    var transforming = false
                    if (drawing) toScan(down.position)?.let { start?.invoke(it); strokeStarted = true }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            // Two fingers always zoom/pan. Drop a stroke that started by accident.
                            if (strokeStarted && !transforming) { cancel?.invoke(); strokeStarted = false }
                            transforming = true
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            pan = clampPan(pan + event.calculatePan(), newZoom)
                            zoom = newZoom
                            event.changes.forEach { it.consume() }
                        } else if (!transforming) {
                            val ch = event.changes.first { it.pressed }
                            if (drawing && strokeStarted) toScan(ch.position)?.let { move?.invoke(it) }
                            else if (zoom > 1f) pan = clampPan(pan + ch.positionChange(), zoom)
                            ch.consume()
                        }
                    }
                }
            }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationX = pan.x; translationY = pan.y
            }
        ) {
            Image(photo.asImageBitmap(), contentDescription = "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (scan != null) Canvas(Modifier.fillMaxSize()) {
                val rect = OutlinePainter.fitRect(photo.width, photo.height, size.width, size.height)
                val s = rect.width() / scan.width
                // Keep lines the same on-screen thickness at any zoom.
                val lineScale = 1f / zoom
                drawIntoCanvas { canvas ->
                    if (showAllEdges) {
                        OutlinePainter.draw(canvas.nativeCanvas, scan.contours, scan, rect, OutlineStyle.SOLID, 0x99FFFFFF.toInt(), 1.2f * density * lineScale)
                    }
                    for (layer in layers) {
                        OutlinePainter.draw(canvas.nativeCanvas, layer.contours, scan, rect, layer.style, layer.color, layer.widthDp * density * lineScale)
                    }
                }
                // The user's finger strokes: thin and translucent white, clearly "your guide".
                // The saved outline is always made of the real edges along it.
                for (stroke in strokes) {
                    if (stroke.size < 2) continue
                    val path = Path().apply {
                        stroke.forEachIndexed { i, p ->
                            val x = rect.left + p.x * s
                            val y = rect.top + p.y * s
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(width = 2.5f * density * lineScale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (zoom > 1.05f) {
            FilledTonalButton(
                onClick = { zoom = 1f; pan = Offset.Zero },
                Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Text("${"%.1f".format(zoom)}× · Reset") }
        }
    }
}
