package app.pareido.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pareido.Navigator
import app.pareido.Screen
import app.pareido.app
import app.pareido.core.ContourTracer
import app.pareido.core.EdgeDetector
import app.pareido.core.EdgeScan
import app.pareido.core.ImageOps
import app.pareido.data.OutlineStyle
import app.pareido.image.Images
import app.pareido.image.OutlinePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Edge detection runs live on small frames; tap capture to analyze a full photo. */
private const val LIVE_MAX_DIM = 320

@Composable
fun LiveCameraScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize()) {
        PareidoTopBar("Live camera", nav)
        if (!granted) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pareido needs the camera to show edges live.")
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
            return
        }
        LiveCamera(nav, app.settings.sensitivity)
    }
}

@Composable
private fun LiveCamera(nav: Navigator, initialSensitivity: Float) {
    val context = LocalContext.current
    val app = context.app
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var scan by remember { mutableStateOf<EdgeScan?>(null) }
    var sensitivity by remember { mutableFloatStateOf(initialSensitivity) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sensitivityRef = remember { floatArrayOf(initialSensitivity) }
    sensitivityRef[0] = sensitivity

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val selector = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val imageCapture = remember {
        ImageCapture.Builder().setResolutionSelector(selector).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val result = runCatching { liveScan(proxy, sensitivityRef[0]) }.getOrNull()
                proxy.close()
                if (result != null) scan = result
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
            } catch (e: Exception) {
                error = "Couldn't start the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    fun capture() {
        capturing = true
        val shot = File(context.cacheDir, "live.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(shot).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val id = app.finds.newId()
                        app.settings.sensitivity = sensitivity
                        val ok = withContext(Dispatchers.IO) { runCatching { Images.importPhoto(shot, app.finds.photoFile(id)) } }
                        capturing = false
                        ok.onSuccess { nav.replace(Screen.Photo(id)) }.onFailure { error = "Couldn't save the photo." }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    error = "Capture failed: ${exception.message}"
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val s = scan ?: return@Canvas
                val rect = OutlinePainter.fitRect(s.width, s.height, size.width, size.height)
                drawIntoCanvas {
                    OutlinePainter.draw(it.nativeCanvas, s.contours, s, rect, OutlineStyle.SOLID, app.settings.outlineColor, 2f * density)
                }
            }
            if (capturing) CircularProgressIndicator()
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("These are the real edges the camera sees. Freeze a frame to find figures in it.", style = MaterialTheme.typography.bodySmall)
            LabeledRow("Edge sensitivity") { Slider(sensitivity, { sensitivity = it }, Modifier.weight(1f)) }
            Button(onClick = { capture() }, Modifier.fillMaxWidth().height(56.dp), enabled = !capturing) { Text("⏺  Freeze & analyze") }
        }
    }
}

/** Upright, downscaled frame -> real edge lines. Runs on the analysis thread. */
private fun liveScan(proxy: ImageProxy, sensitivity: Float): EdgeScan {
    var bmp: Bitmap = proxy.toBitmap()
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation != 0) {
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
    }
    val small = ImageOps.downscale(Images.toPixelImage(Images.scaled(bmp, LIVE_MAX_DIM * 2)), LIVE_MAX_DIM)
    val map = EdgeDetector.detect(small, sensitivity)
    val contours = ContourTracer.trace(map, maxContours = 150, minLengthFraction = 0.04f, simplifyEpsilon = 1.5f)
    return EdgeScan(small.width, small.height, sensitivity, contours)
}
