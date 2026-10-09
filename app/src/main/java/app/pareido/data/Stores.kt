package app.pareido.data

import android.content.Context
import app.pareido.core.EdgeScan
import app.pareido.core.Find
import app.pareido.core.FindStatus
import app.pareido.core.Models
import app.pareido.core.ShapeMatcher
import app.pareido.core.ShapeTemplate
import app.pareido.core.Spending
import app.pareido.core.SpendingSummary
import app.pareido.core.Streak
import app.pareido.core.Streaks
import app.pareido.core.TokenUsage
import app.pareido.core.UsageRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class OutlineStyle(val label: String) { SOLID("Solid"), GLOW("Glow"), CHALK("Chalk") }

/** App settings. The API key is stored in app-private storage on this phone only. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", Models.DEFAULT) ?: Models.DEFAULT
        set(v) = prefs.edit().putString("model", v).apply()

    var outlineColor: Int
        get() = prefs.getInt("outline_color", OUTLINE_COLORS.first())
        set(v) = prefs.edit().putInt("outline_color", v).apply()

    var outlineWidthDp: Float
        get() = prefs.getFloat("outline_width", 4f)
        set(v) = prefs.edit().putFloat("outline_width", v).apply()

    var outlineStyle: OutlineStyle
        get() = runCatching { OutlineStyle.valueOf(prefs.getString("outline_style", "GLOW")!!) }.getOrDefault(OutlineStyle.GLOW)
        set(v) = prefs.edit().putString("outline_style", v.name).apply()

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 0.5f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()

    companion object {
        val OUTLINE_COLORS = listOf(
            0xFFFFB300.toInt(), // amber
            0xFFFF4081.toInt(), // pink
            0xFF00E5FF.toInt(), // cyan
            0xFF76FF03.toInt(), // lime
            0xFFFFFFFF.toInt(), // white
            0xFFE040FB.toInt(), // purple
        )
    }
}

/**
 * Gallery of finds. Each find lives in its own folder: `finds/<id>/photo.jpg` + `find.json`.
 * A folder with a photo but no find.json is a photo the user hasn't analyzed yet.
 */
class FindStore(context: Context) {
    private val root = File(context.filesDir, "finds").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun newId(): String = UUID.randomUUID().toString()
    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun photoFile(id: String) = File(dir(id), "photo.jpg")
    private fun findFile(id: String) = File(dir(id), "find.json")

    @Synchronized
    fun save(find: Find) {
        val tmp = File(dir(find.id), "find.json.tmp")
        tmp.writeText(json.encodeToString(find))
        tmp.renameTo(findFile(find.id))
    }

    @Synchronized
    fun load(id: String): Find? {
        val f = findFile(id)
        return if (f.exists()) runCatching { json.decodeFromString<Find>(f.readText()) }.getOrNull() else null
    }

    @Synchronized
    fun all(): List<Find> =
        (root.listFiles() ?: emptyArray())
            .mapNotNull { d -> File(d, "find.json").takeIf { it.exists() }?.let { load(d.name) } }
            .sortedByDescending { it.createdAtMillis }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** Removes photos that were opened but never analyzed or saved. */
    fun cleanUpUnsaved(olderThanMillis: Long = 24 * 3600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        root.listFiles()?.forEach { d ->
            if (!File(d, "find.json").exists() && d.lastModified() < cutoff) d.deleteRecursively()
        }
    }

    fun streak(today: LocalDate = LocalDate.now()): Streak {
        val zone = ZoneId.systemDefault()
        val days = all()
            .filter { it.status == FindStatus.DONE }
            .map { Instant.ofEpochMilli(it.createdAtMillis).atZone(zone).toLocalDate() }
        return Streaks.compute(days, today)
    }

    /**
     * Figures the user outlined and named: the app's memory for finding them again.
     * Deleting the find forgets the figure.
     */
    fun taughtTemplates(): List<ShapeTemplate> =
        all().filter { it.status == FindStatus.DONE && it.userContours.isNotEmpty() }
            .mapNotNull { f ->
                val label = f.userGuess.trim().ifEmpty { f.figures.firstOrNull()?.label.orEmpty() }
                if (label.isEmpty()) null else ShapeMatcher.template(label, f.userContours)
            }

    fun newFind(id: String, scan: EdgeScan?) =
        Find(id = id, createdAtMillis = System.currentTimeMillis(), mode = app.pareido.core.FindMode.AUTO, status = FindStatus.QUEUED, scan = scan)
}

/** Running log of API usage for the spending tracker. */
class UsageStore(context: Context) {
    private val file = File(context.filesDir, "usage.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<UsageRecord> =
        if (file.exists()) runCatching { json.decodeFromString<List<UsageRecord>>(file.readText()) }.getOrDefault(emptyList())
        else emptyList()

    @Synchronized
    fun record(usage: TokenUsage) {
        if (usage.inputTokens == 0L && usage.outputTokens == 0L) return
        file.writeText(json.encodeToString(all() + UsageRecord(System.currentTimeMillis(), usage)))
    }

    @Synchronized
    fun reset() {
        file.delete()
    }

    fun summary(): SpendingSummary = Spending.summarize(all())
}
