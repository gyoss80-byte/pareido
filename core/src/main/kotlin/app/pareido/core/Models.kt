package app.pareido.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A photo as plain ARGB pixels, so the core never depends on Android's Bitmap. */
class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(argb.size == width * height) { "pixel count ${argb.size} != $width x $height" }
    }
}

@Serializable
data class Pt(val x: Float, val y: Float)

/**
 * One real edge line found in the photo. Points are pixel coordinates in the
 * processing image (see [EdgeScan.width]/[EdgeScan.height]), so every point lies on a detected edge.
 */
@Serializable
data class Contour(val id: Int, val points: List<Pt>) {
    val pixelLength: Float get() = Geometry.polylineLength(points)
}

/** Result of running edge detection + tracing on a photo. */
@Serializable
data class EdgeScan(
    val width: Int,
    val height: Int,
    val sensitivity: Float,
    val contours: List<Contour>,
)

/** A figure Claude recognized, built only from real contours (by id). */
@Serializable
data class Figure(
    val label: String,
    val description: String,
    @SerialName("contour_ids") val contourIds: List<Int>,
    /** 0..1: how strongly the real edges resemble the figure. */
    val confidence: Float,
)

@Serializable
data class TokenUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

/** What Claude said about the whole photo. */
data class AnalysisResult(
    val figures: List<Figure>,
    val comment: String,
    val usage: TokenUsage,
)

/** What Claude said about a shape the user traced. */
data class IdentifyResult(
    val label: String,
    val description: String,
    /** "yes", "no", "partly" or "no_guess". */
    val matchesGuess: String,
    val confidence: Float,
    val comment: String,
    val usage: TokenUsage,
)

@Serializable
enum class FindMode { AUTO, MANUAL, CHALLENGE }

@Serializable
enum class FindStatus { QUEUED, DONE, NOTHING_FOUND, FAILED }

/** One saved entry in the gallery of finds. */
@Serializable
data class Find(
    val id: String,
    val createdAtMillis: Long,
    val mode: FindMode,
    val status: FindStatus,
    val scan: EdgeScan? = null,
    val figures: List<Figure> = emptyList(),
    val comment: String = "",
    /** User's traced stroke, in the same pixel space as [scan]. */
    val userStroke: List<Pt> = emptyList(),
    /** Real contour pieces the user's stroke snapped to. */
    val userContours: List<Contour> = emptyList(),
    val userGuess: String = "",
    val userMatch: String = "",
    val error: String = "",
)

@Serializable
data class UsageRecord(val atMillis: Long, val usage: TokenUsage)
