package io.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.service.TrustedDevice
import io.relay.app.service.TrustedDevices
import io.relay.app.ui.theme.GlassColors
import io.relay.app.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Modifier.sessionCard(colors: GlassColors): Modifier =
    clip(RoundedCornerShape(20.dp)).background(colors.sessionSurface).drawBehind {
        val center = Offset(size.width - 8.dp.toPx(), -18.dp.toPx())
        for (radius in listOf(58.dp, 82.dp)) {
            drawCircle(colors.accentSubtle, radius.toPx(), center, style = Stroke(1.dp.toPx()))
        }
    }

@Composable
internal fun SettingsCard(label: Int, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(glass.radiusMd)
    Column(Modifier.fillMaxWidth().background(glass.settingsSurface, shape).border(1.dp, glass.stroke, shape)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(3.dp, 18.dp).background(glass.accent, shape))
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
        }
        content()
    }
}

@Composable
internal fun TrustedDevicesCard() {
    val context = LocalContext.current
    val store = remember(context) { runCatching { TrustedDevices.get(context) }.getOrNull() }
    if (store == null) {
        SettingsCard(R.string.trusted_title) { Text(stringResource(R.string.trusted_storage_error)) }
        return
    }
    val devices by store.devices.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<TrustedDevice?>(null) }
    var failed by remember { mutableStateOf(false) }
    val glass = LocalGlass.current
    SettingsCard(R.string.trusted_title) {
        if (devices.isEmpty()) Text(stringResource(R.string.trusted_empty),
            style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        devices.forEach { device ->
            Column {
                if (device.name.isNotBlank()) Text(device.name, style = MaterialTheme.typography.titleMedium)
                if (!device.enabled) Text(stringResource(R.string.client_access_disabled), color = glass.warning)
                Text(device.publicKey, fontFamily = FontFamily.Monospace, color = glass.textSecondary,
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                TextButton(onClick = { selected = device }) { Text(stringResource(R.string.trusted_forget)) }
            }
        }
        if (failed) Text(stringResource(R.string.trusted_storage_error), color = glass.error)
    }
    selected?.takeIf { it in devices }?.let { device ->
        AlertDialog(onDismissRequest = { selected = null }, containerColor = glass.settingsSurface,
            title = { Text(stringResource(R.string.trusted_forget)) },
            text = { Column {
                Text(device.name.ifBlank { device.publicKey })
                Text(stringResource(R.string.trusted_forget_body))
            } },
            confirmButton = { TextButton(onClick = {
                selected = null
                scope.launch {
                    failed = withContext(Dispatchers.IO) { runCatching { store.forget(device.publicKey) }.isFailure }
                }
            }) { Text(stringResource(R.string.trusted_forget)) } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.local_network_cancel)) } })
    }
}
