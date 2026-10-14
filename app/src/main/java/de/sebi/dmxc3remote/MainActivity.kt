package de.sebi.dmxc3remote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sebi.dmxc3remote.ui.AppViewModel
import de.sebi.dmxc3remote.ui.ConnState
import de.sebi.dmxc3remote.ui.ConnectScreen
import de.sebi.dmxc3remote.ui.ControlScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFB300),
                    secondary = Color(0xFF4FC3F7),
                    background = Color(0xFF101010),
                    surface = Color(0xFF101010)
                )
            ) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    val c = conn
    if (c is ConnState.Connected && s != null) {
        ControlScreen(vm, s, c.target, Modifier.fillMaxSize().safeDrawingPadding())
    } else {
        ConnectScreen(vm, c, Modifier.fillMaxSize().safeDrawingPadding())
    }
}
