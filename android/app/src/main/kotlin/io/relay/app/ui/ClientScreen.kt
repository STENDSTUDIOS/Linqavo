package io.relay.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.relay.app.R
import io.relay.app.client.ClientIdentity
import io.relay.app.client.ClientNetwork
import io.relay.app.client.ClientOffer
import io.relay.app.client.ClientStage
import io.relay.app.client.ClientTarget
import io.relay.app.client.ClientVpnService
import io.relay.app.client.discoverServers
import io.relay.app.client.pairingPhoto
import io.relay.app.client.readInvitationImage
import io.relay.app.core.LocalNetworkPermission
import io.relay.app.net.PairingServer
import io.relay.app.service.LocalNetworkAccess
import io.relay.app.service.RelayRole
import io.relay.app.ui.theme.GlassChoiceChip
import io.relay.app.ui.theme.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Composable
internal fun ClientScreen(themeMode: String, setTheme: (String) -> Unit, onShareMode: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { ClientIdentity.lastTarget(context) }
    val state by ClientVpnService.state.collectAsState()
    val role by RelayRole.active.collectAsState()
    val active = role == RelayRole.Role.Client
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((saved?.port ?: PairingServer.DEFAULT_PORT).toString()) }
    var pin by rememberSaveable { mutableStateOf(saved?.pin.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(saved?.name.orEmpty()) }
    var serverKey by rememberSaveable { mutableStateOf(saved?.serverKey) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAction by rememberSaveable { mutableStateOf("") }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf(emptyList<ClientOffer>()) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(state.address, state.target) {
        if (state.address != null && state.target?.pin == pin && state.target?.serverKey == serverKey) serverKey = null
    }
    fun useTarget(target: ClientTarget) {
        host = target.host
        port = target.port.toString()
        pin = target.pin
        name = target.name
        serverKey = target.serverKey
        error = null
    }
    fun start(target: ClientTarget) {
        when {
            !LocalNetworkAccess.granted(context) -> error = R.string.client_local_network
            RelayRole.active.value != null -> error = R.string.client_stop_sharing
            else -> runCatching { ClientVpnService.start(context, target) }
                .onFailure { error = R.string.client_tunnel_error }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pending?.let { Json.decodeFromString<ClientTarget>(it) }
        pending = null
        if (target != null && result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            start(target)
        } else {
            error = R.string.client_vpn_denied
        }
    }
    fun connect() {
        val target = ClientTarget(host.trim(), port.toIntOrNull() ?: 0, pin.trim().lowercase(), name, serverKey)
        error = null
        when {
            !target.valid() -> error = R.string.client_invalid_target
            role != null -> error = R.string.client_stop_sharing
            ClientNetwork(context).vpnAlreadyActive() -> error = R.string.client_vpn_conflict
            else -> {
                val intent = VpnService.prepare(context)
                if (intent == null) start(target)
                else {
                    pending = Json.encodeToString(ClientTarget.serializer(), target)
                    consent.launch(intent)
                }
            }
        }
    }
    fun discover() {
        scanning = true
        error = null
        offers = emptyList()
        scope.launch {
            try {
                offers = withContext(Dispatchers.IO) { runCatching { discoverServers() }.getOrDefault(emptyList()) }
                if (offers.isEmpty()) error = R.string.client_no_servers
            } finally {
                scanning = false
            }
        }
    }
    val localPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = permissionAction
        permissionAction = ""
        if (granted && LocalNetworkAccess.granted(context)) {
            if (action == "connect") connect() else if (action == "discover") discover()
        } else {
            error = R.string.client_local_network
        }
    }
    fun withLocalPermission(action: String) {
        if (LocalNetworkAccess.granted(context)) {
            if (action == "connect") connect() else discover()
        } else {
            permissionAction = action
            localPermission.launch(LocalNetworkPermission.NAME)
        }
    }
    fun readPhoto(uri: Uri, delete: Boolean) {
        scanning = true
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) { readInvitationImage(context, uri) }
                if (target == null) error = R.string.client_qr_error else useTarget(target)
            } finally {
                if (delete) pairingPhoto(context).delete()
                scanning = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) readPhoto(it, false)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { savedPhoto ->
        val uri = photoUri?.let(Uri::parse)
        photoUri = null
        if (savedPhoto && uri != null) readPhoto(uri, true) else pairingPhoto(context).delete()
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        if (settingsOpen) settingsOpen = false
        else if (!active && pending == null) onShareMode()
    }
    RelayPages(settingsOpen, { settingsOpen = it }) {
        if (settingsOpen) {
            SessionHeading(stringResource(R.string.nav_settings))
            SettingsCard(R.string.advanced_theme) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChoiceChip(stringResource(R.string.theme_system), themeMode == "system") { setTheme("system") }
                GlassChoiceChip(stringResource(R.string.theme_dark), themeMode == "dark") { setTheme("dark") }
                GlassChoiceChip(stringResource(R.string.theme_light), themeMode == "light") { setTheme("light") }
            }
            }
            TrustedDevicesCard()
        } else {
            Surface(color = io.relay.app.ui.theme.LocalGlass.current.sessionSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                SessionHeading(stringResource(R.string.client_mode_title), Modifier.fillMaxWidth().padding(20.dp))
            }
            Text(stringResource(R.string.client_mode_description))
            Column(Modifier.fillMaxWidth().glassPanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val showingStatus = active || state.target?.let { it.host == host && it.pin == pin } == true
                Text(stringResource(if (showingStatus) state.stage.label else ClientStage.Idle.label),
                    style = MaterialTheme.typography.titleLarge)
                if (showingStatus) {
                    state.target?.let {
                        Text(stringResource(R.string.client_server_name, it.name.ifBlank { it.host }))
                    }
                    state.address?.let { address ->
                        Text(stringResource(R.string.client_vpn_address, address))
                        Text(stringResource(R.string.client_speed, formatBytes(state.download), formatBytes(state.upload)))
                        Text(stringResource(R.string.client_traffic, formatBytes(state.received), formatBytes(state.sent)))
                        Text(stringResource(R.string.client_duration, DateUtils.formatElapsedTime(state.seconds)))
                        if (state.stage == ClientStage.Connected && !state.controlAvailable) {
                            Text(stringResource(R.string.client_presence_unavailable))
                        }
                    }
                    state.keyFingerprint?.let {
                        Text(stringResource(R.string.client_compare_key))
                        SelectionContainer {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                    }
                }
                (error ?: state.detail.takeIf { showingStatus })?.let { Text(stringResource(it)) }
                if ((error ?: state.detail.takeIf { showingStatus }) == R.string.client_local_network) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }) { Text(stringResource(R.string.local_network_settings)) }
                }
                if (error == R.string.client_vpn_conflict) {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
                        Text(stringResource(R.string.client_vpn_settings))
                    }
                }
                if (active) {
                    Button(onClick = { ClientVpnService.disconnect(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.client_mode_disconnect))
                    }
                } else {
                    val ready = !scanning && pending == null && role == null
                    Text(stringResource(R.string.client_fingerprint_help))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = ready, onClick = { withLocalPermission("discover") }) {
                            Text(stringResource(if (scanning) R.string.client_searching else R.string.client_discover))
                        }
                        OutlinedButton(enabled = ready, onClick = {
                            runCatching {
                                val file = pairingPhoto(context)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.pairing-photo", file)
                                photoUri = uri.toString()
                                camera.launch(uri)
                            }.onFailure {
                                photoUri = null
                                error = R.string.client_camera_unavailable
                            }
                        }) { Text(stringResource(R.string.client_qr_camera)) }
                        OutlinedButton(enabled = ready, onClick = { picker.launch("image/*") }) {
                            Text(stringResource(R.string.client_qr_file))
                        }
                    }
                    for (offer in offers) {
                        TextButton(enabled = ready, onClick = {
                            if (host != offer.host || port != offer.port.toString()) {
                                pin = ""
                                serverKey = null
                            }
                            host = offer.host
                            port = offer.port.toString()
                            name = offer.name
                            error = null
                        }) { Text(stringResource(R.string.client_offer, offer.name.ifBlank { offer.host }, offer.code)) }
                    }
                    TargetField(host, R.string.client_host, ready) {
                        host = it.take(15)
                        name = ""
                        serverKey = null
                        error = null
                    }
                    TargetField(port, R.string.client_port, ready, KeyboardType.Number) { port = it.take(5); error = null }
                    TargetField(pin, R.string.client_fingerprint, ready) { pin = it.take(96); serverKey = null; error = null }
                    Button(enabled = ready, onClick = { withLocalPermission("connect") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.client_connect_action))
                    }
                    TextButton(enabled = pending == null, onClick = onShareMode) { Text(stringResource(R.string.action_start)) }
                }
            }

        }
    }
}

@Composable
private fun TargetField(value: String, label: Int, enabled: Boolean,
                        keyboard: KeyboardType = KeyboardType.Ascii, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(stringResource(label)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
