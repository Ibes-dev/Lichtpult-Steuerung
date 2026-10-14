package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraConstants
import de.sebi.dmxc3remote.net.UmbraTarget

@Composable
fun ConnectScreen(vm: AppViewModel, conn: ConnState, modifier: Modifier = Modifier) {
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf(vm.prefs.host) }
    var port by remember { mutableStateOf(vm.prefs.port.toString()) }
    var netId by remember { mutableStateOf(vm.prefs.netId) }
    var clientName by remember { mutableStateOf(vm.prefs.clientName) }
    var username by remember { mutableStateOf(vm.prefs.username) }
    var password by remember { mutableStateOf(vm.prefs.password) }
    var advanced by remember { mutableStateOf(false) }

    fun persistIdentity() {
        vm.prefs.clientName = clientName.ifBlank { "Android Remote" }
        vm.prefs.username = username
        vm.prefs.password = password
    }

    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mit DMXControl verbinden", style = MaterialTheme.typography.headlineSmall)

        when (conn) {
            is ConnState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Verbinde mit ${conn.target.label} (Versuch ${conn.attempt}) …")
                TextButton(onClick = vm::disconnect) { Text("Abbrechen") }
            }
            is ConnState.Failed -> Column {
                Text("Fehler: ${conn.message}", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Neuer Versuch in ${conn.retryInSec} s")
                    TextButton(onClick = vm::disconnect) { Text("Abbrechen") }
                }
            }
            else -> Unit
        }
        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearError) { Text("OK") }
            }
        }

        Text("Im Netzwerk gefunden", style = MaterialTheme.typography.titleMedium)
        if (discovered.isEmpty()) {
            Text(
                "Noch kein DMXControl gefunden. Handy und PC müssen im selben Netz sein und der Access Point " +
                    "muss Multicast durchlassen. Alternativ unten die IP-Adresse eingeben.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        discovered.values.sortedBy { it.clientName }.forEach { d ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    persistIdentity()
                    vm.connect(d.toTarget())
                }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(d.clientName.ifBlank { d.hostname }, style = MaterialTheme.typography.titleSmall)
                    Text("${d.host}:${d.port}  ·  ${d.hostname}", style = MaterialTheme.typography.bodySmall)
                    if (d.projectName.isNotBlank()) Text("Projekt: ${d.projectName}", style = MaterialTheme.typography.bodySmall)
                    Text("NetID: ${d.netId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = {
                        persistIdentity()
                        vm.requestAdmission(d)
                    }) { Text("Freigabe anfragen") }
                }
            }
        }

        HorizontalDivider()
        Text("Manuell verbinden", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                host, { host = it.trim() }, label = { Text("IP / Host") },
                singleLine = true, modifier = Modifier.weight(3f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        OutlinedTextField(
            netId, { netId = it.trim() }, label = { Text("NetID") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            clientName, { clientName = it }, label = { Text("Clientname") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Anmeldedaten ausblenden" else "Anmeldedaten ändern")
        }
        if (advanced) {
            OutlinedTextField(
                username, { username = it }, label = { Text("Benutzer") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Passwort") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = host.isNotBlank(),
                onClick = {
                    persistIdentity()
                    vm.connect(
                        UmbraTarget(
                            host = host,
                            port = port.toIntOrNull() ?: UmbraConstants.UMBRA_PORT,
                            netId = netId
                        )
                    )
                }
            ) { Text("Verbinden") }
            OutlinedButton(onClick = {
                port = UmbraConstants.UMBRA_PORT.toString()
                username = "DMXCDefault"
                password = "DMXC3"
            }) { Text("Standardwerte") }
        }
    }
}
}
