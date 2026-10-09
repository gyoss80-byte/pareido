package app.pareido.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pareido.Navigator
import app.pareido.Screen
import app.pareido.app
import app.pareido.core.Find
import app.pareido.core.FindMode
import app.pareido.core.FindStatus
import app.pareido.image.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(nav: Navigator) {
    val app = LocalContext.current.app
    var finds by remember { mutableStateOf<List<Find>?>(null) }
    LaunchedEffect(Unit) { finds = withContext(Dispatchers.IO) { app.finds.all() } }

    Column(Modifier.fillMaxSize()) {
        PareidoTopBar("My finds", nav)
        val list = finds
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "Nothing here yet. Analyze a photo and your finds will be saved here.",
                Modifier.padding(24.dp),
            )
            else -> LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { find -> FindTile(find) { nav.go(Screen.Photo(find.id)) } }
            }
        }
    }
}

@Composable
private fun FindTile(find: Find, onClick: () -> Unit) {
    val app = LocalContext.current.app
    var thumb by remember(find.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(find.id) {
        thumb = withContext(Dispatchers.IO) { runCatching { Images.load(app.finds.photoFile(find.id), 360) }.getOrNull() }
    }
    val title = when (find.status) {
        FindStatus.QUEUED -> "⏳ Waiting for internet"
        FindStatus.FAILED -> "⚠️ Couldn't analyze"
        FindStatus.NOTHING_FOUND -> "Nothing found"
        FindStatus.DONE -> when (find.mode) {
            FindMode.CHALLENGE -> "🏁 ${find.userGuess} vs ${find.figures.firstOrNull()?.label ?: "?"}"
            FindMode.MANUAL -> "✏️ ${find.figures.firstOrNull()?.label ?: "?"}"
            FindMode.AUTO -> "✨ " + find.figures.joinToString(", ") { it.label }
        }
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(title, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(find.createdAtMillis)),
            Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
