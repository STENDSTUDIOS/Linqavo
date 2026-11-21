package io.relay.app.e2e

import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.relay.app.core.PeerActionRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.R
import io.relay.app.core.PeerSnapshot
import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayload
import io.relay.app.service.ConnectionRepository
import io.relay.app.ui.HomeScreen
import io.relay.app.ui.PeerList
import io.relay.app.ui.theme.RelayBackground
import io.relay.app.ui.theme.RelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI projection only. This is intentionally not evidence of three live tunnels. */
@RunWith(AndroidJUnit4::class)
class MultiPeerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun individualActionsIdentifyTheCorrectDevice() {
        val peers = (0..2).map { PeerSnapshot("key-$it", "PC-$it", "10.13.37.${it+2}/32",
            true, "connected", 1, 2, 3, 100, 200) }
        val actions = mutableListOf<Pair<String,String>>()
        compose.setContent {
            RelayTheme {
                RelayBackground {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PeerList(peers, "test-session") { request -> actions.add(request.publicKey to request.operation) }
                    }
                }
            }
        }
        for (peer in peers) {
            compose.onNodeWithText(peer.name).performScrollTo().performClick()
            compose.onNodeWithText(peer.address).assertExists()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onAllNodesWithText(context.getString(R.string.client_disconnect))[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<Pair<String,String>>(), actions) }
        compose.onNodeWithTag("peer-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("key-1" to "disable"), actions) }
        compose.onNodeWithText("PC-0").assertExists()
        compose.onNodeWithText("PC-2").assertExists()
    }

    @Test fun revokeCancelBackAndStaleTargetsNeverExecute() {
        val peer = PeerSnapshot("immutable", "Test PC", "10.13.37.2/32", true, "connected", 1, 0, 0, 0, 0)
        val peers = mutableStateOf(listOf(peer))
        val actions = mutableListOf<PeerActionRequest>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            RelayTheme { RelayBackground {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PeerList(peers.value, "session") { actions.add(it) }
                }
            } }
        }
        fun open() {
            val revoke = context.getString(R.string.client_revoke)
            if (compose.onAllNodesWithText(revoke).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText(peer.name).performScrollTo().performClick()
            }
            compose.onNodeWithText(revoke).performScrollTo().performClick()
        }
        open()
        compose.onNodeWithText(context.getString(R.string.client_revoke_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.size) }
        compose.onNodeWithText(context.getString(R.string.local_network_cancel)).performClick()
        compose.onNodeWithTag("peer-confirmation").assertDoesNotExist()
        open()
        compose.onNodeWithTag("peer-confirmation").assertIsDisplayed()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // A composed dialog can already be visible before its own window gets input focus.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                        it !== compose.activity.window.decorView && it.hasWindowFocus()
                    }
                }
            }
        }
        automation.executeShellCommand("input keyevent KEYCODE_BACK").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("peer-confirmation").fetchSemanticsNodes().isEmpty() }
        open()
        compose.runOnIdle { peers.value = listOf(peer.copy(enrolledAt = 2)) }
        compose.onNodeWithTag("peer-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions.size) }
        open()
        compose.runOnIdle { peers.value = emptyList() }
        compose.onNodeWithTag("peer-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions.size) }
    }

    @Test fun revokeConfirmationUsesIntendedIdentityAndEnableNeedsNoDialog() {
        val peers = mutableStateOf(listOf(PeerSnapshot("key", "PC", "10.13.37.2/32", true, "connected", 9, 0, 0, 0, 0)))
        val actions = mutableListOf<PeerActionRequest>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { RelayTheme { RelayBackground {
            Column(Modifier.verticalScroll(rememberScrollState())) { PeerList(peers.value, "session") { actions.add(it) } }
        } } }
        compose.onNodeWithText("PC").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.client_revoke)).performScrollTo().performClick()
        compose.onNodeWithTag("peer-confirm").performClick()
        compose.runOnIdle {
            assertEquals(PeerActionRequest("key", "revoke", "session", 9, true), actions.single())
            peers.value = listOf(peers.value.single().copy(active = false, status = "disabled"))
        }
        compose.onNodeWithText(context.getString(R.string.client_reconnect)).performScrollTo().performClick()
        compose.onNodeWithTag("peer-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals("activate", actions.last().operation) }
    }

    @Test fun disconnectReconnectAndUnknownUpdateTheActualHomeScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val live = (0..2).map { PeerSnapshot("ui-$it", "PC-$it", "10.13.37.${it+2}/32", true, "connected", 1, 2, 3, 100, 200) }
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
        ConnectionRepository.dispatch("start") { ConnectionState.Preparing }
        ConnectionRepository.dispatch("ready") {
            ConnectionState.Advertising(QrPayload(v=2, mode="wireguard", host="192.0.2.1", port=51820), null, "42")
        }
        ConnectionRepository.publishPeers(live)
        compose.setContent {
            val state by ConnectionRepository.state.collectAsState()
            val peers by ConnectionRepository.peers.collectAsState()
            RelayTheme { RelayBackground {
                HomeScreen(state, true, emptySet(), "dark", true, emptyList(),
                    {}, {}, {}, {}, {}, {}, {}, {}, peers = peers)
            } }
        }
        fun assertCard(key: String, resource: Int) {
            compose.onNode(hasText(context.getString(resource)) and hasAnyAncestor(hasTestTag("peer-$key")),
                useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
        }
        try {
            compose.runOnIdle {
                ConnectionRepository.publishPeers(live.mapIndexed { i, peer ->
                    peer.copy(status = if (i == 0) "degraded" else "offline")
                })
            }
            assertCard("ui-0", R.string.client_presence_unavailable)
            compose.runOnIdle { assertEquals(1, (ConnectionRepository.state.value as ConnectionState.Connected).clientCount) }
            compose.runOnIdle { ConnectionRepository.publishPeers(live.mapIndexed { i,p -> if (i==0) p.copy(status="offline") else p }) }
            assertCard("ui-0", R.string.client_offline)
            assertCard("ui-1", R.string.client_connected)
            compose.runOnIdle { assertEquals(2, (ConnectionRepository.state.value as ConnectionState.Connected).clientCount) }
            compose.runOnIdle { ConnectionRepository.publishPeers(live) }
            assertCard("ui-0", R.string.client_connected)
            compose.runOnIdle { ConnectionRepository.publishPeers(live.map { it.copy(status="unknown") }) }
            assertCard("ui-2", R.string.client_unknown)
            compose.runOnIdle {
                assertEquals(true, ConnectionRepository.state.value is ConnectionState.Advertising)
                assertEquals(3, ConnectionRepository.peers.value.size)
            }
        } finally {
            compose.runOnIdle {
                ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
                ConnectionRepository.peers.value = emptyList()
            }
        }
    }
}
