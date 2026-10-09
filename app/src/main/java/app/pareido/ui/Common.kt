package app.pareido.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pareido.Navigator

private val colors = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF8EC5FC),
    background = Color(0xFF0F1B2D),
    surface = Color(0xFF16263D),
    surfaceVariant = Color(0xFF1F3350),
    onBackground = Color(0xFFE8EEF7),
    onSurface = Color(0xFFE8EEF7),
)

@Composable
fun PareidoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PareidoTopBar(title: String, nav: Navigator, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (nav.canGoBack) TextButton(onClick = { nav.back() }) { Text("‹ Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

fun matchStrength(confidence: Float): String = when {
    confidence >= 0.8f -> "Strong match"
    confidence >= 0.55f -> "Good match"
    confidence >= 0.35f -> "Fair match"
    else -> "A stretch"
} + " · ${(confidence * 100).toInt()}%"
