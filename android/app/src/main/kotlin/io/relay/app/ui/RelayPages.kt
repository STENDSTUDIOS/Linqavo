package io.relay.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.relay.app.R
import io.relay.app.ui.theme.LocalGlass

@Composable
internal fun RelayPages(settings: Boolean, navigate: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
            .verticalScroll(if (settings) settingsScroll else homeScroll)
            .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            for (destination in listOf(false, true)) {
                val label = if (destination) R.string.nav_settings else R.string.nav_home
                val icon = if (destination) R.drawable.nav_settings else R.drawable.nav_home
                NavigationBarItem(selected = settings == destination, onClick = { navigate(destination) },
                    icon = { Icon(painterResource(icon), contentDescription = null) },
                    label = { Text(stringResource(label)) })
            }
        }
    }
}

@Composable
internal fun SessionHeading(title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.relay_mark), contentDescription = null,
            tint = LocalGlass.current.accent, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}
