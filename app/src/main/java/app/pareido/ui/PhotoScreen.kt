package app.pareido.ui

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pareido.Navigator
import app.pareido.app
import app.pareido.core.ClaudeException
import app.pareido.core.Contour
import app.pareido.core.EdgeScan
import app.pareido.core.EdgeScanner
import app.pareido.core.Figure
import app.pareido.core.Find
import app.pareido.core.FindMode
import app.pareido.core.FindStatus
import app.pareido.core.Pt
import app.pareido.core.ShapeFinder
import app.pareido.core.Snapping
import app.pareido.data.OutlineStyle
import app.pareido.image.Images
import app.pareido.work.AnalyzeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Phase { LOADING, IDLE, ANALYZING, RESULT, DRAWING, ASKING, MANUAL_RESULT, CHALLENGE_RESULT, QUEUED, FAILED }

/** Second colour for "your" outline in challenge mode, so it differs from Claude's. */
private const val USER_COLOR = 0xFF00E5FF.toInt()

@Composable
fun PhotoScreen(nav: Navigator, findId: String) {
    val context = LocalContext.current
    val app = context.app
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.LOADING) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var scan by remember { mutableStateOf<EdgeScan?>(null) }
    var saved by remember { mutableStateOf<Find?>(null) }
    var sensitivity by remember { mutableStateOf(settings.sensitivity) }
    var showEdges by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    val figures = remember { mutableStateListOf<Figure>() }
    var selected by remember { mutableStateOf(0) }
    var comment by remember { mutableStateOf("") }

    var drawMode by remember { mutableStateOf(FindMode.MANUAL) }
    val strokes = remember { mutableStateListOf<List<Pt>>() }
    var guess by remember { mutableStateOf("") }
    var userContours by remember { mutableStateOf<List<Contour>>(emptyList()) }
    var userMatch by remember { mutableStateOf("") }
    var coverage by remember { mutableStateOf(0f) }

    val hasKey = settings.apiKey.isNotBlank()
    val style = settings.outlineStyle
    val color = settings.outlineColor
    val width = settings.outlineWidthDp

    fun applyFind(f: Find) {
        saved = f
        scan = f.scan
        figures.clear(); figures.addAll(f.figures)
        comment = f.comment
        userContours = f.userContours
        strokes.clear(); if (f.userStroke.isNotEmpty()) strokes.add(f.userStroke)
        guess = f.userGuess
        userMatch = f.userMatch
        phase = when {
            f.status == FindStatus.QUEUED -> Phase.QUEUED
            f.status == FindStatus.FAILED -> Phase.FAILED.also { message = f.error }
            f.mode == FindMode.MANUAL -> Phase.MANUAL_RESULT
            f.mode == FindMode.CHALLENGE -> Phase.CHALLENGE_RESULT
            else -> Phase.RESULT
        }
    }

    // Load the photo and either the saved find or a fresh edge scan.
    LaunchedEffect(findId) {
        val (bmp, f) = withContext(Dispatchers.IO) {
            Images.load(app.finds.photoFile(findId)) to app.finds.load(findId)
        }
        photo = bmp
        if (f != null) applyFind(f) else phase = Phase.IDLE
    }

    // Re-scan when the sensitivity slider moves (only before any result exists).
    LaunchedEffect(photo, sensitivity, phase == Phase.IDLE || phase == Phase.DRAWING) {
        val p = photo ?: return@LaunchedEffect
        if (saved != null || (phase != Phase.IDLE && phase != Phase.DRAWING)) return@LaunchedEffect
        delay(200)
        scan = withContext(Dispatchers.Default) { EdgeScanner.scan(Images.toPixelImage(p), sensitivity) }
    }

    fun save(f: Find) {
        saved = f
        scope.launch(Dispatchers.IO) { app.finds.save(f) }
    }

    fun errorText(e: Throwable) = (e as? ClaudeException)?.message ?: "Something went wrong: ${e.message}"

    fun findFigures(more: Boolean = false) {
        val p = photo ?: return
        val s = scan ?: return
        phase = Phase.ANALYZING
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { app.claude.findFigures(p, s, if (more) figures.map { it.label } else emptyList()) }
                if (more) {
                    val known = figures.map { it.label.lowercase() }.toSet()
                    val fresh = result.figures.filter { it.label.lowercase() !in known }
                    figures.addAll(fresh)
                    if (fresh.isEmpty()) message = "Claude couldn't see anything else in these edges."
                    else selected = figures.size - fresh.size
                } else {
                    figures.clear(); figures.addAll(result.figures); selected = 0
                    comment = result.comment
                }
                phase = Phase.RESULT
                if (figures.isNotEmpty()) {
                    val base = saved ?: Find(findId, System.currentTimeMillis(), FindMode.AUTO, FindStatus.DONE, scan = s)
                    save(base.copy(status = FindStatus.DONE, figures = figures.toList(), comment = comment, scan = s))
                }
            } catch (e: ClaudeException) {
                if (e.kind == ClaudeException.Kind.OFFLINE && !more) {
                    // Offline queue: keep the photo + edges and analyze when back online.
                    save(Find(findId, System.currentTimeMillis(), FindMode.AUTO, FindStatus.QUEUED, scan = s))
                    AnalyzeWorker.enqueue(context, findId)
                    phase = Phase.QUEUED
                } else {
                    message = errorText(e)
                    phase = if (more) Phase.RESULT else Phase.IDLE
                }
            } catch (e: Exception) {
                message = errorText(e)
                phase = if (more) Phase.RESULT else Phase.IDLE
            }
        }
    }

    /** Snap all finger strokes to real edges. Returns false (with a message) if nothing real is nearby. */
    fun snapStrokes(): Boolean {
        val s = scan ?: return false
        val snaps = strokes.filter { it.size >= 2 }.map { Snapping.snap(it, s) }
        val pieces = snaps.flatMap { it.contours }
        val totalPoints = strokes.sumOf { it.size }.coerceAtLeast(1)
        coverage = snaps.zip(strokes.filter { it.size >= 2 }).sumOf { (sn, st) -> (sn.strokeCoverage * st.size).toDouble() }.toFloat() / totalPoints
        if (pieces.isEmpty()) {
            message = "There are no real edges along your drawing. Trace closer to the shape's edge, or raise the edge sensitivity."
            return false
        }
        userContours = pieces
        return true
    }

    fun askClaudeAboutDrawing() {
        val p = photo ?: return
        val s = scan ?: return
        if (!snapStrokes()) return
        phase = Phase.ASKING
        message = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { app.claude.identify(p, s, userContours, guess) }
                figures.clear()
                figures.add(Figure(r.label, r.description, userContours.map { it.id }.distinct(), r.confidence))
                comment = r.comment
                userMatch = r.matchesGuess
                phase = Phase.MANUAL_RESULT
                save(
                    Find(
                        findId, System.currentTimeMillis(), FindMode.MANUAL, FindStatus.DONE, scan = s,
                        figures = figures.toList(), comment = comment, userStroke = strokes.flatten(),
                        userContours = userContours, userGuess = guess, userMatch = userMatch,
                    )
                )
            } catch (e: Exception) {
                message = errorText(e); phase = Phase.DRAWING
            }
        }
    }

    // ---- No-API-key mode: shapes come from the phone, names come from you ----

    fun findShapesLocally() {
        val s = scan ?: return
        message = null
        figures.clear(); figures.addAll(ShapeFinder.candidates(s)); selected = 0
        comment = ""
        phase = Phase.RESULT
        if (figures.isNotEmpty()) {
            save(Find(findId, System.currentTimeMillis(), FindMode.AUTO, FindStatus.DONE, scan = s, figures = figures.toList()))
        }
    }

    fun saveOwnTracing() {
        val s = scan ?: return
        if (guess.isBlank()) { message = "Type what you see, then save."; return }
        if (!snapStrokes()) return
        message = null
        figures.clear()
        figures.add(Figure(guess.trim(), "", userContours.map { it.id }.distinct(), coverage, byClaude = false))
        comment = ""
        userMatch = ""
        phase = Phase.MANUAL_RESULT
        save(
            Find(
                findId, System.currentTimeMillis(), FindMode.MANUAL, FindStatus.DONE, scan = s,
                figures = figures.toList(), userStroke = strokes.flatten(),
                userContours = userContours, userGuess = guess,
            )
        )
    }

    fun renameFigure(index: Int, name: String) {
        if (name.isBlank() || index !in figures.indices) return
        figures[index] = figures[index].copy(label = name.trim())
        saved?.let { save(it.copy(figures = figures.toList())) }
    }

    fun revealChallenge() {
        val p = photo ?: return
        val s = scan ?: return
        if (guess.isBlank()) { message = "Type what you see first, then reveal!"; return }
        if (!snapStrokes()) return
        phase = Phase.ASKING
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { app.claude.findFigures(p, s) }
                figures.clear(); figures.addAll(result.figures); selected = 0
                comment = result.comment
                phase = Phase.CHALLENGE_RESULT
                save(
                    Find(
                        findId, System.currentTimeMillis(), FindMode.CHALLENGE, FindStatus.DONE, scan = s,
                        figures = figures.toList(), comment = comment, userStroke = strokes.flatten(),
                        userContours = userContours, userGuess = guess,
                    )
                )
            } catch (e: Exception) {
                message = errorText(e); phase = Phase.DRAWING
            }
        }
    }

    fun figureContours(f: Figure?): List<Contour> {
        val s = scan ?: return emptyList()
        return if (f == null) emptyList() else s.contours.filter { it.id in f.contourIds }
    }

    fun shareResult() {
        val p = photo ?: return
        val s = scan ?: return
        val fig = figures.getOrNull(selected)
        val (contours, caption, extra) = when (phase) {
            Phase.MANUAL_RESULT -> Triple(userContours, "${fig?.label ?: "?"} · spotted with Pareido", emptyList())
            Phase.CHALLENGE_RESULT -> Triple(
                figureContours(fig),
                "Me: $guess · Claude: ${fig?.label ?: "nothing"} · Pareido",
                listOf(userContours to USER_COLOR),
            )
            else -> Triple(figureContours(fig), "${fig?.label ?: ""} · spotted with Pareido", emptyList())
        }
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                Images.renderShareImage(p, s, contours, caption, style, color, width * p.width / 400f, extra)
            }
            Images.share(context, bmp, "I found ${fig?.label?.let { "a $it" } ?: "something"} in this photo with Pareido 👀")
        }
    }

    fun friendChallenge() {
        Images.shareFile(
            context, app.finds.photoFile(findId),
            "👀 What do you see in this? Tell me before I show you what I found! (Pareido)",
        )
    }

    // ---------- Layout ----------
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Column(Modifier.fillMaxSize()) {
        PareidoTopBar(
            title = when (phase) {
                Phase.DRAWING -> if (drawMode == FindMode.CHALLENGE) "Challenge: trace it" else "Trace what you see"
                Phase.RESULT, Phase.MANUAL_RESULT, Phase.CHALLENGE_RESULT -> "Find"
                else -> "Photo"
            },
            nav = nav,
            actions = {
                if (saved != null) TextButton(onClick = {
                    app.finds.delete(findId); nav.back()
                }) { Text("Delete") }
            },
        )

        val p = photo
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (p == null) {
                CircularProgressIndicator()
            } else {
                val layers = buildList {
                    if (showOutline) when (phase) {
                        Phase.RESULT -> add(OutlineLayer(figureContours(figures.getOrNull(selected)), color, style, width))
                        Phase.MANUAL_RESULT -> add(OutlineLayer(userContours, color, style, width))
                        Phase.CHALLENGE_RESULT -> {
                            add(OutlineLayer(userContours, USER_COLOR, OutlineStyle.CHALK, width))
                            add(OutlineLayer(figureContours(figures.getOrNull(selected)), color, style, width))
                        }
                        else -> Unit
                    }
                }
                val drawing = phase == Phase.DRAWING
                PhotoCanvas(
                    photo = p, scan = scan, layers = layers, modifier = Modifier.fillMaxSize(),
                    showAllEdges = showEdges && (phase == Phase.IDLE || drawing),
                    strokes = if (drawing || phase == Phase.ASKING) strokes else emptyList(),
                    onStrokeStart = if (drawing) ({ pt: Pt -> strokes.add(listOf(pt)); Unit }) else null,
                    onStrokeMove = if (drawing) ({ pt: Pt ->
                        if (strokes.isNotEmpty()) strokes[strokes.lastIndex] = strokes.last() + pt
                    }) else null,
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().heightIn(max = maxPanel).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (phase == Phase.IDLE || phase == Phase.DRAWING) {
                LabeledRow("Edge sensitivity") {
                    Slider(sensitivity, { sensitivity = it }, Modifier.weight(1f))
                }
                LabeledRow("Show detected edges (${scan?.contours?.size ?: 0})") {
                    Switch(showEdges, { showEdges = it })
                }
            }

            when (phase) {
                Phase.LOADING -> Unit
                Phase.IDLE -> {
                    if (hasKey) {
                        Button(onClick = { findFigures() }, Modifier.fillMaxWidth(), enabled = scan != null) { Text("✨ Find figures") }
                    } else {
                        Button(onClick = { findShapesLocally() }, Modifier.fillMaxWidth(), enabled = scan != null) { Text("🔍 Find shapes") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { drawMode = FindMode.MANUAL; strokes.clear(); phase = Phase.DRAWING }, Modifier.weight(1f)) { Text("✏️ I see something") }
                        OutlinedButton(
                            onClick = { drawMode = FindMode.CHALLENGE; strokes.clear(); phase = Phase.DRAWING },
                            Modifier.weight(1f), enabled = hasKey,
                        ) { Text("🏁 Challenge me") }
                    }
                    if (!hasKey) Text(
                        "No API key: Pareido highlights the real shapes and you name them. Add a key in Settings to have Claude spot figures and to unlock Challenge mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { friendChallenge() }) { Text("📨 Send to a friend: what do they see?") }
                }
                Phase.ANALYZING, Phase.ASKING -> {
                    Text("Claude is looking at the edges…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Phase.DRAWING -> {
                    Text(
                        if (drawMode == FindMode.CHALLENGE) "Trace the figure you see, name it, then reveal what Claude sees."
                        else "Trace around what you see with your finger. Pareido keeps only the real edges along your line."
                    )
                    OutlinedTextField(
                        guess, { guess = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(if (drawMode == FindMode.CHALLENGE || !hasKey) "What do you see?" else "What do you see? (optional)") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
                        OutlinedButton(onClick = { phase = Phase.IDLE; strokes.clear(); message = null }) { Text("Cancel") }
                        Button(
                            onClick = {
                                when {
                                    drawMode == FindMode.CHALLENGE -> revealChallenge()
                                    hasKey -> askClaudeAboutDrawing()
                                    else -> saveOwnTracing()
                                }
                            },
                            Modifier.weight(1f), enabled = strokes.isNotEmpty(),
                        ) { Text(if (drawMode == FindMode.CHALLENGE) "Reveal" else if (hasKey) "Ask Claude" else "Save") }
                    }
                }
                Phase.RESULT -> ResultPanel(
                    figures, selected, { selected = it }, comment, showOutline, { showOutline = it },
                    onMore = if (hasKey) ({ findFigures(more = true) }) else null,
                    onShare = { shareResult() },
                    onRename = { name -> renameFigure(selected, name) },
                )
                Phase.MANUAL_RESULT -> {
                    val f = figures.firstOrNull()
                    Text(f?.label?.replaceFirstChar { it.uppercase() } ?: "?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    f?.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    if (guess.isNotBlank() && f?.byClaude == true) Text(
                        "You said \"$guess\": " + when (userMatch) {
                            "yes" -> "Claude agrees! 🎉"
                            "partly" -> "Claude partly agrees."
                            "no" -> "Claude sees something different."
                            else -> ""
                        }
                    )
                    if (coverage > 0f) Text("${(coverage * 100).toInt()}% of your tracing follows real edges.", style = MaterialTheme.typography.bodySmall)
                    if (comment.isNotBlank()) Text(comment, style = MaterialTheme.typography.bodySmall)
                    OutlineToggleAndShare(showOutline, { showOutline = it }, { shareResult() })
                }
                Phase.CHALLENGE_RESULT -> {
                    val ai = figures.getOrNull(selected)
                    val same = figures.any { it.label.contains(guess.trim(), ignoreCase = true) || guess.contains(it.label, ignoreCase = true) }
                    Text(
                        if (same) "You both saw a ${guess.trim()}! 🎉"
                        else "You saw: ${guess.trim()} · Claude saw: ${ai?.label ?: "nothing convincing"}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    Text("Your edges are dashed cyan; Claude's are in your outline colour.", style = MaterialTheme.typography.bodySmall)
                    if (figures.size > 1) FigureChips(figures, selected) { selected = it }
                    ai?.let { Text("${it.description} (${matchStrength(it.confidence)})") }
                    OutlineToggleAndShare(showOutline, { showOutline = it }, { shareResult() })
                }
                Phase.QUEUED -> Text("📶 You're offline. This photo is saved in My finds and will be analyzed automatically when you're back online.")
                Phase.FAILED -> {
                    Text("This one couldn't be analyzed.")
                    OutlinedButton(onClick = { saved = null; phase = Phase.IDLE; findFigures() }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun FigureChips(figures: List<Figure>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        figures.forEachIndexed { i, f ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(f.label) })
        }
    }
}

@Composable
private fun OutlineToggleAndShare(show: Boolean, onShow: (Boolean) -> Unit, onShare: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Outline")
        Switch(show, onShow)
        Box(Modifier.weight(1f))
        Button(onClick = onShare) { Text("Share") }
    }
}

@Composable
private fun ResultPanel(
    figures: List<Figure>, selected: Int, onSelect: (Int) -> Unit, comment: String,
    showOutline: Boolean, onShowOutline: (Boolean) -> Unit, onMore: (() -> Unit)?, onShare: () -> Unit,
    onRename: (String) -> Unit,
) {
    if (figures.isEmpty()) {
        Text(if (onMore != null) "No figure found" else "No clear shapes", style = MaterialTheme.typography.headlineSmall)
        Text(comment.ifBlank {
            if (onMore != null) "Claude didn't see a convincing figure in these edges."
            else "There aren't enough strong edges here. Try raising the edge sensitivity."
        })
        Text("Try another angle, adjust the edge sensitivity, or trace what you see yourself.", style = MaterialTheme.typography.bodySmall)
        onMore?.let { OutlinedButton(onClick = it) { Text("Look again") } }
        return
    }
    val f = figures[selected.coerceIn(figures.indices)]
    if (figures.size > 1) FigureChips(figures, selected, onSelect)
    Text(f.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    if (f.byClaude) {
        Text(matchStrength(f.confidence), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(f.description)
    } else {
        // Found on the phone: the outline is real, the name is yours.
        var name by remember(selected, f.label) { mutableStateOf("") }
        Text("This is a real shape from your photo. What does it look like to you?")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("Name it") })
            Button(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") }
        }
    }
    if (comment.isNotBlank()) Text(comment, style = MaterialTheme.typography.bodySmall)
    OutlineToggleAndShare(showOutline, onShowOutline, onShare)
    onMore?.let { OutlinedButton(onClick = it, Modifier.fillMaxWidth()) { Text("🔄 What else could this be?") } }
}
