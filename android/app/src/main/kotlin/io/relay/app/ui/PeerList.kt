package io.relay.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import io.relay.app.core.PeerActionRequest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.relay.app.R
import io.relay.app.core.PeerSnapshot
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel
import java.text.DateFormat
import java.util.Date

@Composable
fun PeerList(
    peers: List<PeerSnapshot>,
    sessionId: String? = null,
    pending: Set<String> = emptySet(),
    onAction: (PeerActionRequest) -> Unit,
) {
    val glass = LocalGlass.current
    val context = LocalContext.current
    var confirmation by remember { mutableStateOf<PeerActionRequest?>(null) }
    val target = confirmation?.let { request -> peers.find { it.publicKey == request.publicKey } }
    val valid = confirmation?.matches(peers, sessionId) == true
    LaunchedEffect(confirmation, valid) {
        if (confirmation != null && !valid) {
            confirmation = null
            Toast.makeText(context, R.string.client_action_stale, Toast.LENGTH_LONG).show()
        }
    }
    if (confirmation != null && target != null && valid) {
        val request = confirmation!!
        val revoke = request.operation == "revoke"
        val name = target.name.ifBlank { stringResource(R.string.client_generic) }
        AlertDialog(
            modifier = Modifier.testTag("peer-confirmation"),
            containerColor = glass.backgroundBase,
            titleContentColor = glass.textPrimary,
            textContentColor = glass.textPrimary,
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (revoke) R.string.client_revoke_title else R.string.client_disable_title)) },
            text = { Text(stringResource(if (revoke) R.string.client_revoke_body else R.string.client_disable_body, "\u2068$name\u2069"),
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            dismissButton = { TextButton(onClick = { confirmation = null },
                colors = ButtonDefaults.textButtonColors(contentColor = glass.textPrimary)) {
                Text(stringResource(R.string.local_network_cancel))
            } },
            confirmButton = { TextButton(
                modifier = Modifier.testTag("peer-confirm"),
                enabled = request.publicKey !in pending,
                onClick = {
                    confirmation = null
                    if (request.matches(peers, sessionId)) onAction(request)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = if (revoke) glass.error else glass.textPrimary),
            ) { Text(stringResource(if (revoke) R.string.client_revoke_confirm else R.string.client_disconnect)) } },
        )
    }
    if (peers.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.clients_authorized),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
        for (peer in peers) androidx.compose.runtime.key(peer.publicKey) {
            Column(
                Modifier.testTag("peer-${peer.publicKey}").fillMaxWidth().glassPanel(radius = glass.radiusMd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var expanded by remember(sessionId, peer.enrolledAt) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                    onClickLabel = stringResource(if (expanded) R.string.hide_peer_details else R.string.show_peer_details)
                ) { expanded = !expanded }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name.ifBlank { stringResource(R.string.client_generic) },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(stringResource(when (peer.status) {
                            "connected" -> R.string.client_connected
                            "degraded" -> R.string.client_presence_unavailable
                            "reconnecting" -> R.string.client_reconnecting
                            "unknown" -> R.string.client_unknown
                            "disabled" -> R.string.client_disabled
                            "offline" -> R.string.client_offline
                            else -> R.string.client_authorized
                        }), style = MaterialTheme.typography.bodySmall,
                            color = if (peer.status == "connected") glass.accent else glass.textSecondary)
                    }
                    Text(if (expanded) "−" else "+", color = glass.accent)
                }
                if (expanded) {
                    Text(peer.address, style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr,
                    ), color = glass.textSecondary)
                    Text(stringResource(R.string.client_traffic, formatBytes(peer.bytesReceived), formatBytes(peer.bytesSent)),
                        style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                    val handshake = if (peer.lastHandshake > 0)
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, androidx.compose.ui.platform.LocalLocale.current.platformLocale).format(Date(peer.lastHandshake * 1000))
                        else stringResource(R.string.client_never)
                    Text(stringResource(R.string.client_handshake, handshake),
                        style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !sessionId.isNullOrBlank() && peer.publicKey !in pending,
                            onClick = {
                                val request = PeerActionRequest(peer.publicKey, if (peer.active) "disable" else "activate",
                                    sessionId.orEmpty(), peer.enrolledAt, peer.active)
                                if (peer.active) confirmation = request else if (request.matches(peers, sessionId)) onAction(request)
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = glass.textPrimary),
                        ) {
                            Text(stringResource(if (peer.active) R.string.client_disconnect else R.string.client_reconnect))
                        }
                        TextButton(enabled = !sessionId.isNullOrBlank() && peer.publicKey !in pending,
                            onClick = { confirmation = PeerActionRequest(peer.publicKey, "revoke", sessionId.orEmpty(), peer.enrolledAt, peer.active) },
                            colors = ButtonDefaults.textButtonColors(contentColor = glass.error)) {
                            Text(stringResource(R.string.client_revoke))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
