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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.sebi.dmxc3remote.ui.AppViewModel
import de.sebi.dmxc3remote.ui.ConnState
import de.sebi.dmxc3remote.ui.ConnectScreen
import de.sebi.dmxc3remote.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        val lastCrash = CrashReporter.take(this)
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
                Surface(Modifier.fillMaxSize()) {
                    var crash by remember { mutableStateOf(lastCrash) }
                    val c = crash
                    if (c != null) CrashDialog(c) { crash = null } else App()
                }
            }
        }
    }
}

@Composable
private fun CrashDialog(text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Letzter Absturz") },
        text = {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Kopieren") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Schließen") } }
    )
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session
    val c = conn
    if (c is ConnState.Connected && s != null) {
        MainScreen(vm, s, c.target, Modifier.fillMaxSize().safeDrawingPadding())
    } else {
        ConnectScreen(vm, c, Modifier.fillMaxSize().safeDrawingPadding())
    }
}
