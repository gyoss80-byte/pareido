package app.pareido.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.pareido.Navigator
import app.pareido.Screen
import app.pareido.app
import app.pareido.core.Streak
import app.pareido.image.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var streak by remember { mutableStateOf<Streak?>(null) }
    val hasKey = remember { app.settings.apiKey.isNotBlank() }

    LaunchedEffect(Unit) { streak = withContext(Dispatchers.IO) { app.finds.streak() } }

    fun importAndOpen(copy: (File) -> Unit) {
        busy = true
        scope.launch {
            val id = app.finds.newId()
            val ok = withContext(Dispatchers.IO) { runCatching { copy(app.finds.photoFile(id)) } }
            busy = false
            ok.onSuccess { nav.go(Screen.Photo(id)) }
                .onFailure { error = "Couldn't open that photo: ${it.message}"; app.finds.delete(id) }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) importAndOpen { dest -> Images.importPhoto(context, uri, dest) }
    }
    val cameraFile = remember { File(context.cacheDir, "camera.jpg") }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) importAndOpen { dest -> Images.importPhoto(cameraFile, dest) }
    }
    // The app declares CAMERA (for live mode), so Android refuses the camera app
    // until that permission is granted. Ask first instead of crashing.
    fun launchCamera() {
        error = null
        take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile))
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = "Pareido needs camera access to take a photo. You can still pick one from your gallery."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("☁️ Pareido", fontSize = 40.sp, style = MaterialTheme.typography.headlineLarge)
        Text(
            "Find the figures hiding in clouds, walls and floors.\nOnly real edges, never made-up lines.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )

        streak?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    when {
                        s.current > 0 && s.spottedToday -> "🔥 ${s.current}-day streak · best ${s.best}"
                        s.current > 0 -> "🔥 ${s.current}-day streak — spot something today to keep it going!"
                        else -> "Spot something today to start a streak" + if (s.best > 0) " (best: ${s.best} days)" else ""
                    },
                    Modifier.padding(16.dp),
                )
            }
        }

        if (!hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Works without a key: Pareido finds the real shapes and you name them. Add an Anthropic API key in Settings to have Claude spot figures too.")
                    OutlinedButton(onClick = { nav.go(Screen.Settings) }, Modifier.padding(top = 8.dp)) { Text("Add a key (optional)") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (busy) CircularProgressIndicator()
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else askCamera.launch(Manifest.permission.CAMERA)
        }, Modifier.fillMaxWidth().height(56.dp), enabled = !busy) { Text("📷  Take a photo") }
        Button(onClick = {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, Modifier.fillMaxWidth().height(56.dp), enabled = !busy) { Text("🖼️  Pick from gallery") }
        Button(onClick = { nav.go(Screen.Live) }, Modifier.fillMaxWidth().height(56.dp), enabled = !busy) { Text("🎥  Live camera") }
        OutlinedButton(onClick = { nav.go(Screen.Gallery) }, Modifier.fillMaxWidth().height(52.dp)) { Text("✨  My finds") }
        OutlinedButton(onClick = { nav.go(Screen.Settings) }, Modifier.fillMaxWidth().height(52.dp)) { Text("⚙️  Settings") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
