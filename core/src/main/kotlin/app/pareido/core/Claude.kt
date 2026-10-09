package app.pareido.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.util.Base64

object Models {
    const val OPUS = "claude-opus-5-5"
    const val SONNET = "claude-sonnet-5-5"
    const val HAIKU = "claude-haiku-5-5"
    const val DEFAULT = OPUS

    val choices = listOf(
        OPUS to "Claude Opus 5.5 — best at spotting shapes",
        SONNET to "Claude Sonnet 5.5 — about half the cost",
        HAIKU to "Claude Haiku 5.5 — cheapest, least imaginative",
    )
}

class ClaudeException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NO_KEY, BAD_KEY, OFFLINE, RATE_LIMITED, REFUSED, TRUNCATED, BAD_RESPONSE, OTHER }
}

/** One request to Claude: some JPEG images, a prompt, and the JSON schema the answer must follow. */
data class ClaudeRequest(
    val model: String,
    val system: String,
    val jpegImages: List<ByteArray>,
    val text: String,
    val schemaJson: String,
)

data class ClaudeReply(val text: String, val usage: TokenUsage)

fun interface ClaudeTransport {
    fun send(request: ClaudeRequest): ClaudeReply
}

/** Real transport using the official Anthropic Java SDK. */
class AnthropicTransport(apiKey: String) : ClaudeTransport {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    override fun send(request: ClaudeRequest): ClaudeReply {
        val message = try {
            client.beta().messages().create(buildParams(request))
        } catch (e: UnauthorizedException) {
            throw ClaudeException(ClaudeException.Kind.BAD_KEY, "Your API key was rejected. Check it in Settings.", e)
        } catch (e: PermissionDeniedException) {
            throw ClaudeException(ClaudeException.Kind.BAD_KEY, "Your API key can't use this model.", e)
        } catch (e: RateLimitException) {
            throw ClaudeException(ClaudeException.Kind.RATE_LIMITED, "Too many requests right now. Try again in a minute.", e)
        } catch (e: AnthropicIoException) {
            throw ClaudeException(ClaudeException.Kind.OFFLINE, "Couldn't reach Claude (no connection?).", e)
        } catch (e: AnthropicServiceException) {
            throw ClaudeException(ClaudeException.Kind.OTHER, "Claude returned an error (${e.statusCode()}).", e)
        }

        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL ->
                throw ClaudeException(ClaudeException.Kind.REFUSED, "Claude declined to analyze this image.")
            BetaStopReason.MAX_TOKENS ->
                throw ClaudeException(ClaudeException.Kind.TRUNCATED, "Claude's answer was cut off. Try again.")
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val u = message.usage()
        val usage = TokenUsage(
            model = message.model().asString(),
            inputTokens = u.inputTokens(),
            outputTokens = u.outputTokens(),
            cacheReadTokens = u.cacheReadInputTokens().orElse(0L),
            cacheWriteTokens = u.cacheCreationInputTokens().orElse(0L),
        )
        return ClaudeReply(text, usage)
    }

    companion object {
        /** Server-side refusal fallback. Haiku has none, so it is only sent for Opus/Sonnet. */
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        internal fun buildParams(request: ClaudeRequest): MessageCreateParams {
            val blocks = request.jpegImages.map { jpeg ->
                BetaContentBlockParam.ofImage(
                    BetaImageBlockParam.builder()
                        .source(
                            BetaBase64ImageSource.builder()
                                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                .data(Base64.getEncoder().encodeToString(jpeg))
                                .build()
                        )
                        .build()
                )
            } + BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(request.text).build())

            val schema = BetaJsonOutputFormat.Schema.builder().apply {
                (Json.parseToJsonElement(request.schemaJson) as JsonObject).forEach { (key, value) ->
                    putAdditionalProperty(key, JsonValue.from(value.toPlain()))
                }
            }.build()

            val builder = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(16000L)
                .system(request.system)
                .addUserMessageOfBetaContentBlockParams(blocks)
                .outputConfig(
                    BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.MEDIUM)
                        .format(BetaJsonOutputFormat.builder().schema(schema).build())
                        .build()
                )
            if (!request.model.startsWith("claude-haiku")) {
                builder.addBeta(FALLBACK_BETA)
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return builder.build()
        }

        private fun JsonElement.toPlain(): Any? = when (this) {
            is JsonNull -> null
            is JsonObject -> mapValues { it.value.toPlain() }
            is JsonArray -> map { it.toPlain() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                else -> doubleOrNull
            }
        }
    }
}

/** Builds the prompts, sends them, and checks every answer against the real contours. */
class PareidoAnalyzer(private val transport: ClaudeTransport, private val model: String = Models.DEFAULT) {

    /**
     * @param photoJpeg the untouched photo.
     * @param annotatedJpeg the same photo with each contour drawn in colour and labelled with its id.
     * @param exclude labels already found, for "what else could this be?".
     */
    fun findFigures(photoJpeg: ByteArray, annotatedJpeg: ByteArray, scan: EdgeScan, exclude: List<String> = emptyList()): AnalysisResult {
        if (scan.contours.isEmpty()) {
            return AnalysisResult(emptyList(), "No clear edges in this photo. Try raising the edge sensitivity.", TokenUsage(model, 0, 0))
        }
        val reply = transport.send(
            ClaudeRequest(
                model = model,
                system = Prompts.SYSTEM,
                jpegImages = listOf(photoJpeg, annotatedJpeg),
                text = Prompts.findFigures(scan, exclude),
                schemaJson = Prompts.FIGURES_SCHEMA,
            )
        )
        val obj = parseObject(reply.text)
        val validIds = scan.contours.map { it.id }.toSet()
        val figures = obj["figures"]?.jsonArray.orEmpty().mapNotNull { el ->
            val f = el.jsonObject
            val ids = f["contour_ids"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
                .filter { it in validIds } // never trust an id that isn't a real edge
                .distinct()
            val confidence = f["confidence"]?.jsonPrimitive?.float?.coerceIn(0f, 1f) ?: 0f
            val label = f["label"]?.jsonPrimitive?.content.orEmpty().trim()
            if (ids.isEmpty() || label.isEmpty() || confidence < MIN_CONFIDENCE) null
            else Figure(label, f["description"]?.jsonPrimitive?.content.orEmpty(), ids, confidence)
        }.sortedByDescending { it.confidence }
        return AnalysisResult(figures, obj["comment"]?.jsonPrimitive?.content.orEmpty(), reply.usage)
    }

    /**
     * @param highlightedJpeg the photo with only the user's snapped real edges drawn on it.
     */
    fun identifyTraced(photoJpeg: ByteArray, highlightedJpeg: ByteArray, guess: String): IdentifyResult {
        val reply = transport.send(
            ClaudeRequest(
                model = model,
                system = Prompts.SYSTEM,
                jpegImages = listOf(photoJpeg, highlightedJpeg),
                text = Prompts.identify(guess),
                schemaJson = Prompts.IDENTIFY_SCHEMA,
            )
        )
        val obj = parseObject(reply.text)
        fun str(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
        val match = str("matches_guess").ifEmpty { "no_guess" }
        return IdentifyResult(
            label = str("label"),
            description = str("description"),
            matchesGuess = if (guess.isBlank()) "no_guess" else match,
            confidence = obj["confidence"]?.jsonPrimitive?.float?.coerceIn(0f, 1f) ?: 0f,
            comment = str("comment"),
            usage = reply.usage,
        )
    }

    private fun parseObject(text: String): JsonObject =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ClaudeException(ClaudeException.Kind.BAD_RESPONSE, "Claude's answer wasn't readable. Try again.", e)
        }

    companion object {
        /** Figures below this are too much of a stretch to show. */
        const val MIN_CONFIDENCE = 0.25f
    }
}

object Prompts {
    val SYSTEM = """
        You are the eye behind Pareido, an app that finds pareidolia: figures that people can see
        in clouds, walls, floors, stains and other random textures.

        You receive two images:
        1. The original, untouched photo.
        2. The same photo with real edge lines drawn on it. A computer-vision algorithm traced these
           lines directly from the photo's pixels, and each line is numbered.

        The app's promise is that it never invents lines. Every outline it shows must be made only of
        those numbered, real edges. So:
        - Only propose a figure if a set of the numbered lines really traces its outline or its key
          features (for example a head, ear, snout or wing).
        - Don't propose a figure that would need important parts the lines don't show.
        - Any kind of figure is fine: animals, faces, people, creatures, objects, letters, places.
        - Be playful but honest. If nothing convincing is there, return no figures and say so in the
          comment. Finding nothing is a valid result.
        - confidence (0 to 1) is how clearly the chosen lines resemble the figure, where 0.9 means
          most people would see it right away and 0.3 means it's a stretch.
    """.trimIndent()

    fun findFigures(scan: EdgeScan, exclude: List<String>): String = buildString {
        appendLine("The photo was analysed at ${scan.width}x${scan.height} px. Numbered edge lines (bounding box in those pixels):")
        for (c in scan.contours) {
            val b = Geometry.bounds(c.points)
            appendLine("#${c.id}: x ${b.minX.toInt()}-${b.maxX.toInt()}, y ${b.minY.toInt()}-${b.maxY.toInt()}, length ${c.pixelLength.toInt()} px")
        }
        appendLine()
        appendLine("Find up to 4 distinct figures. For each one, list exactly the line numbers that form it.")
        if (exclude.isNotEmpty()) {
            appendLine("These were already found, so look for different interpretations: ${exclude.joinToString(", ")}.")
        }
    }

    fun identify(guess: String): String = buildString {
        appendLine("The person traced a shape on this photo with their finger. The second image shows only the")
        appendLine("real edges that lie along their tracing, highlighted in bright colour.")
        appendLine("What does that highlighted shape look like? Describe what you see in it.")
        if (guess.isNotBlank()) {
            appendLine("The person thinks it looks like: \"${guess.trim()}\". Say whether you agree (yes, partly or no).")
        } else {
            appendLine("The person didn't say what they see, so set matches_guess to \"no_guess\".")
        }
    }

    val FIGURES_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["figures", "comment"],
          "properties": {
            "figures": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["label", "description", "contour_ids", "confidence"],
                "properties": {
                  "label": {"type": "string", "description": "Short name, e.g. 'rabbit'"},
                  "description": {"type": "string", "description": "One playful sentence on where you see it"},
                  "contour_ids": {"type": "array", "items": {"type": "integer"}},
                  "confidence": {"type": "number"}
                }
              }
            },
            "comment": {"type": "string", "description": "One short sentence about the photo overall"}
          }
        }
    """.trimIndent()

    val IDENTIFY_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["label", "description", "matches_guess", "confidence", "comment"],
          "properties": {
            "label": {"type": "string"},
            "description": {"type": "string"},
            "matches_guess": {"type": "string", "enum": ["yes", "partly", "no", "no_guess"]},
            "confidence": {"type": "number"},
            "comment": {"type": "string"}
          }
        }
    """.trimIndent()
}
