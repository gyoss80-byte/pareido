package app.pareido.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pareido.Navigator
import app.pareido.app
import app.pareido.core.Models
import app.pareido.core.SpendingSummary
import app.pareido.data.OutlineStyle
import app.pareido.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SettingsScreen(nav: Navigator) {
    val app = LocalContext.current.app
    val s = app.settings
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var color by remember { mutableStateOf(s.outlineColor) }
    var width by remember { mutableStateOf(s.outlineWidthDp) }
    var style by remember { mutableStateOf(s.outlineStyle) }
    var sensitivity by remember { mutableStateOf(s.sensitivity) }
    var spending by remember { mutableStateOf<SpendingSummary?>(null) }
    LaunchedEffect(Unit) { spending = withContext(Dispatchers.IO) { app.usage.summary() } }

    Column(Modifier.fillMaxSize()) {
        PareidoTopBar("Settings", nav)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                apiKey, { apiKey = it; s.apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "Create a key at console.anthropic.com → API Keys. It's stored only on this phone and sent only to Anthropic.",
                style = MaterialTheme.typography.bodySmall,
            )
            Models.choices.forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(model == id, onClick = { model = id; s.model = id }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(model == id, onClick = { model = id; s.model = id })
                    Text(label)
                }
            }

            HorizontalDivider()
            Text("Spending (estimate)", style = MaterialTheme.typography.titleMedium)
            spending?.let { sp ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("This month: ${sp.thisMonthAnalyses} analyses · ${usd(sp.thisMonthUsd)}")
                        Text("All time: ${sp.analyses} analyses · ${usd(sp.totalUsd)}")
                        Text(
                            "Estimated from token counts at list prices. Your real bill is at console.anthropic.com.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedButton(onClick = { app.usage.reset(); spending = app.usage.summary() }) { Text("Reset tracker") }
            }

            HorizontalDivider()
            Text("Outline look", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Settings.OUTLINE_COLORS.forEach { c ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == color) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { color = c; s.outlineColor = c }
                    )
                }
            }
            LabeledRow("Thickness") {
                Slider(width, { width = it; s.outlineWidthDp = it }, Modifier.weight(1f), valueRange = 2f..10f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineStyle.entries.forEach { st ->
                    Row(
                        Modifier.selectable(style == st, onClick = { style = st; s.outlineStyle = st }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(style == st, onClick = { style = st; s.outlineStyle = st })
                        Text(st.label)
                    }
                }
            }
            Text("Styles only change how lines look. They always follow the real edges.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Edge detection", style = MaterialTheme.typography.titleMedium)
            LabeledRow("Default sensitivity") {
                Slider(sensitivity, { sensitivity = it; s.sensitivity = it }, Modifier.weight(1f))
            }
            Text("Higher finds fainter edges (good for wispy clouds or wall stains). Lower keeps only bold outlines.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun usd(v: Double) = String.format(Locale.US, if (v < 1) "$%.3f" else "$%.2f", v)
