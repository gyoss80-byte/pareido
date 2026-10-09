package app.pareido

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.pareido.ui.GalleryScreen
import app.pareido.ui.HomeScreen
import app.pareido.ui.LiveCameraScreen
import app.pareido.ui.PareidoTheme
import app.pareido.ui.PhotoScreen
import app.pareido.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data object Live : Screen
    data object Gallery : Screen
    data object Settings : Screen
    /** A photo (new or saved). [findId] names its folder in the find store. */
    data class Photo(val findId: String) : Screen
}

/** Tiny back stack, so the app needs no navigation library. */
class Navigator(private val stack: MutableList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun go(screen: Screen) { stack.add(screen) }
    fun back() { if (canGoBack) stack.removeAt(stack.lastIndex) }
    /** Replace the current screen (e.g. live camera -> captured photo). */
    fun replace(screen: Screen) { stack[stack.lastIndex] = screen }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PareidoTheme {
                // Surface sets the default text colour (light on navy) for every screen.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Navigator(stack) }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    when (val screen = nav.current) {
        Screen.Home -> HomeScreen(nav)
        Screen.Live -> LiveCameraScreen(nav)
        Screen.Gallery -> GalleryScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        is Screen.Photo -> PhotoScreen(nav, screen.findId)
    }
}
