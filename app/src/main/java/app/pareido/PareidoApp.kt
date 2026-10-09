package app.pareido

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pareido.core.AnalysisResult
import app.pareido.core.AnthropicTransport
import app.pareido.core.ClaudeException
import app.pareido.core.Contour
import app.pareido.core.EdgeScan
import app.pareido.core.IdentifyResult
import app.pareido.core.PareidoAnalyzer
import app.pareido.data.FindStore
import app.pareido.data.Settings
import app.pareido.data.UsageStore
import app.pareido.image.Images

class PareidoApp : Application() {
    lateinit var settings: Settings
    lateinit var finds: FindStore
    lateinit var usage: UsageStore
    lateinit var claude: ClaudeService

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        finds = FindStore(this)
        usage = UsageStore(this)
        claude = ClaudeService(this)
        Thread { finds.cleanUpUnsaved() }.start()
    }
}

val Context.app: PareidoApp get() = applicationContext as PareidoApp

/** Glue between the screens and the core analyzer: builds images, records spending. Call off the main thread. */
class ClaudeService(private val context: Context) {
    private val app get() = context.app

    private fun analyzer(): PareidoAnalyzer {
        val key = app.settings.apiKey
        if (key.isBlank()) throw ClaudeException(ClaudeException.Kind.NO_KEY, "Add your Anthropic API key in Settings first.")
        return PareidoAnalyzer(AnthropicTransport(key), app.settings.model)
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun findFigures(photo: Bitmap, scan: EdgeScan, exclude: List<String> = emptyList()): AnalysisResult {
        val a = analyzer()
        if (!isOnline()) throw ClaudeException(ClaudeException.Kind.OFFLINE, "You're offline.")
        val taught = app.finds.taughtTemplates().map { it.label }.distinct()
        val result = a.findFigures(Images.photoForClaude(photo), Images.annotatedForClaude(photo, scan), scan, exclude, taught)
        app.usage.record(result.usage)
        return result
    }

    fun identify(photo: Bitmap, scan: EdgeScan, snapped: List<Contour>, guess: String): IdentifyResult {
        val a = analyzer()
        if (!isOnline()) throw ClaudeException(ClaudeException.Kind.OFFLINE, "You're offline. This mode needs internet.")
        val result = a.identifyTraced(Images.photoForClaude(photo), Images.highlightedForClaude(photo, scan, snapped), guess)
        app.usage.record(result.usage)
        return result
    }
}
