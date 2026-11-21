package io.relay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.relay.app.R
import io.relay.app.ui.theme.GlassChoiceChip
import io.relay.app.ui.theme.LocalGlass
import io.relay.app.ui.theme.glassPanel

@Composable
internal fun ClientLimitSetting(maximum: Int, enabled: Boolean, onSet: (Int) -> Unit) {
    val glass = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.client_limit, maximum),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
        )
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (limit in 1..3) {
                GlassChoiceChip(limit.toString(), selected = limit == maximum, enabled = enabled) { onSet(limit) }
            }
        }
    }
}

@Composable
internal fun FingerprintCard(fingerprint: String) {
    val glass = LocalGlass.current
    Column(
        modifier = Modifier.fillMaxWidth().glassPanel(radius = glass.radiusMd).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.secure_pairing_title),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        FingerprintText(fingerprint, stringResource(R.string.enrollment_server_fingerprint, "").trim())
    }
}

/** Display the original complete identity; wrapping never inserts or removes characters. */
@Composable
internal fun FingerprintText(fingerprint: String, explanation: String) {
    val glass = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        SelectionContainer {
            Text(
                text = fingerprint,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 24.sp,
                    textDirection = TextDirection.Ltr,
                ),
                color = glass.textPrimary,
                softWrap = true,
            )
        }
    }
}
