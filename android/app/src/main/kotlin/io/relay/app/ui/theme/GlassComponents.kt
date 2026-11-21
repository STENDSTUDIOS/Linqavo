package io.relay.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A quiet, accessible single-choice control using the original glass/teal tokens. */
@Composable
fun GlassChoiceChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(999.dp)
    val markerWidth = with(LocalDensity.current) { 14.sp.toDp() }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) glass.accentSubtle else glass.fill)
            .border(if (selected) 2.dp else 1.dp, if (selected) glass.accent else glass.stroke, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .minimumInteractiveComponentSize()
            .widthIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) Text("✓", style = MaterialTheme.typography.bodySmall, color = glass.accent,
                modifier = Modifier.width(markerWidth).clearAndSetSemantics { testTag = "choice-selected-$text" })
            else Spacer(Modifier.width(markerWidth))
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) glass.accent else glass.textSecondary,
            )
        }
    }
}

/**
 * The glass material: a translucent layered surface with a light-from-above
 * gradient fill and a specular top edge. Elevation is communicated by
 * translucency, not drop shadows (see docs/design). The layered-translucency
 * fallback used here must never appear broken; backdrop blur is layered on where
 * the platform supports it.
 */
fun Modifier.glassPanel(radius: Dp = 24.dp, raised: Boolean = false): Modifier = composed {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(radius)
    val fill = if (raised) glass.fillRaised else glass.fill
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(fill.copy(alpha = (fill.alpha + 0.03f).coerceAtMost(1f)), fill),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(glass.strokeHighlight, glass.stroke, glass.stroke),
            ),
            shape = shape,
        )
}

/** The quiet backdrop the glass refracts: a deep neutral vertical gradient with a faint accent bloom. */
@Composable
fun RelayBackground(content: @Composable () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        glass.backgroundGradientTop,
                        glass.backgroundBase,
                        glass.backgroundGradientBottom,
                    )
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(glass.accent.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(0.5f, 0f),
                    radius = 1200f,
                )
            ),
    ) {
        content()
    }
}
