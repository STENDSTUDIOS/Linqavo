package io.relay.app.e2e

import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.PeerSnapshot
import io.relay.app.core.QrPayload
import io.relay.app.core.WgParams
import io.relay.app.ui.ClientScreen
import io.relay.app.ui.SettingsContent
import io.relay.app.ui.FingerprintCard
import io.relay.app.ui.HomeScreen
import io.relay.app.ui.theme.RelayBackground
import io.relay.app.ui.theme.RelayTheme
import io.relay.app.ui.theme.LocalGlass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith

/** Real Compose rendering with public fixtures and inert callbacks: no service or pairing starts. */
@RunWith(AndroidJUnit4::class)
class VisualConsistencyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var scene by mutableStateOf("idle")
    private var theme by mutableStateOf("dark")
    private var language by mutableStateOf("en")
    private var fontScale by mutableStateOf(1f)
    private var systemDark by mutableStateOf(true)
    private var maximum by mutableStateOf(3)
    private var chosenLimit = 0
    private var updateClicks = 0
    private var startClicks = 0

    private fun setContent() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList.forLanguageTags(language))
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val localized = remember(language, systemDark) { base.createConfigurationContext(config) }
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density, fontScale),
            ) {
                key(scene, theme, language, fontScale, systemDark) {
                    RelayTheme(theme) {
                        val foreground = LocalContentColor.current
                        val expected = LocalGlass.current.textPrimary
                        SideEffect { assertEquals("Implicit text must follow the resolved Relay theme", expected, foreground) }
                        RelayBackground {
                            when (scene) {
                                "advanced" -> Column(
                                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                                        .verticalScroll(rememberScrollState()).padding(24.dp),
                                ) { AdvancedFixture() }
                                "identity" -> Box(
                                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                                    contentAlignment = Alignment.TopCenter,
                                ) { FingerprintCard(FINGERPRINT) }
                                else -> HomeFixture()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeFixture() {
        HomeScreen(
            state = when (scene) {
                "connected" -> ConnectionState.Connected(PAYLOAD, null, "42", 2, 512_000, 2_048_000)
                "pairing", "approval" -> ConnectionState.Advertising(PAYLOAD, null, "42")
                else -> ConnectionState.Idle
            },
            batteryExempt = true, warnings = emptySet(), themeMode = theme,
            fullModeAvailable = true, logs = emptyList(), onStart = { startClicks++ },
            onStop = {}, onRetry = {}, onDismissError = {}, onAllowBattery = {},
            onDismissWarning = {}, onSetTheme = { theme = it }, onClearLogs = {},
            peers = if (scene == "connected") PEERS else emptyList(),
            maximumClients = maximum, onSetMaximumClients = { chosenLimit = it; maximum = it },
            enrollmentFingerprint = if (scene in listOf("pairing", "connected", "approval")) FINGERPRINT else null,
            onGetUpdate = { updateClicks++ },
            pendingClient = if (scene == "approval") "preview-session:$FINGERPRINT" else null,
            pendingClientName = if (scene == "approval") "Work laptop" else null,
        )
    }

    @Composable
    private fun AdvancedFixture() {
        SettingsContent(
            state = ConnectionState.Idle, themeMode = theme, logs = emptyList(),
            onSetTheme = { theme = it }, onClearLogs = {}, onShareLogs = {},
            maximumClients = maximum, onSetMaximumClients = { chosenLimit = it; maximum = it },
            updateAvailable = null, onGetUpdate = { updateClicks++ },
        )
    }

    @Test fun captureDarkLightAndSystemPresentation() {
        setContent()
        for (mode in listOf("dark", "light")) {
            for (view in listOf("idle", "pairing", "connected", "identity", "advanced", "approval")) {
                compose.runOnIdle { theme = mode; scene = view }
                compose.waitForIdle()
                if (view == "pairing") {
                    val qr = compose.activity.getString(R.string.qr_content_description)
                    compose.waitUntil(timeoutMillis = 15_000) {
                        compose.onAllNodesWithContentDescription(qr).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                if (view == "identity") assertCompleteFingerprintFits()
                capture("visual-$mode-$view")
                if (view == "connected") {
                    compose.onNodeWithText("Travel laptop").performScrollTo().performClick()
                    capture("visual-$mode-devices")
                }
                if (view == "advanced") {
                    compose.onNodeWithText(compose.activity.getString(R.string.update_check_manual)).performScrollTo()
                    capture("visual-$mode-diagnostics")
                }
            }
        }
        for (dark in listOf(true, false)) {
            compose.runOnIdle { theme = "system"; systemDark = dark; scene = "idle" }
            compose.waitForIdle()
            capture("visual-system-${if (dark) "dark" else "light"}-idle")
        }
    }

    @Test fun persianAndLargerTextKeepTheEntireIdentity() {
        setContent()
        for (mode in listOf("dark", "light")) {
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle { theme = mode; language = "fa"; fontScale = scale; scene = "identity" }
                compose.waitForIdle()
                assertCompleteFingerprintFits()
                capture("visual-$mode-fa-identity-${scale.toInt()}x")
                if (scale == 2f) {
                    compose.runOnIdle { scene = "advanced" }
                    compose.waitForIdle()
                    val indicator = compose.onNodeWithTag("choice-selected-3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val label = compose.onNodeWithText("3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    check(!indicator.overlaps(label)) { "Selected marker overlaps the capacity label at large font scale" }
                    capture("visual-$mode-fa-advanced-2x")
                }
            }
            compose.runOnIdle { fontScale = 1f; scene = "advanced" }
            compose.waitForIdle()
            capture("visual-$mode-fa-advanced")
        }
    }

    @Test fun relocatedControlsRetainTheirCallbacksAndEnablement() {
        setContent()
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.update_check_manual)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.client_limit, 3)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.action_start)).performClick()
        compose.runOnIdle { assertEquals(1, startClicks) }
        compose.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
        compose.onNodeWithText("1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, chosenLimit) }
        compose.onNodeWithText(context.getString(R.string.update_check_manual)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, updateClicks); scene = "connected" }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
        for (limit in 1..3) compose.onNodeWithText(limit.toString()).performScrollTo().assertIsNotEnabled()
    }

    @Test fun clientSettingsReturnToTheSameRole() {
        compose.setContent { RelayTheme("dark") { RelayBackground {
            ClientScreen("dark", {}) {}
        } } }
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.theme_dark)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.client_discover)).assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.client_discover)).assertExists()
        compose.onNodeWithText(context.getString(R.string.theme_dark)).assertDoesNotExist()
    }

    private fun assertCompleteFingerprintFits() {
        compose.onNodeWithText(FINGERPRINT).assertIsDisplayed()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(FINGERPRINT).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        check(results.isNotEmpty() && results.none { it.hasVisualOverflow }) { "Fingerprint clipped or truncated" }
        check(results.single().layoutInput.text.text == FINGERPRINT) { "Identity text was altered" }
    }

    private fun capture(label: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // Compose idleness does not wait for SurfaceFlinger or dialog-window
        // transitions. Let the actual display present before a full-device shot.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(600)
        DeviceEvidence.screenshot(label)
    }

    companion object {
        @BeforeClass @JvmStatic fun clearPriorVisualCaptures() {
            // Only this test's generated images, never app settings or other evidence.
            DeviceEvidence.directory.listFiles()?.filter {
                it.isFile && it.name.matches(Regex("[0-9]+-visual-[A-Za-z0-9-]+\\.png"))
            }?.forEach { check(it.delete()) { "Cannot replace prior visual capture" } }
        }
        // Public visual fixtures only; none is a device identity or private key.
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private val PAYLOAD = QrPayload(
            v = 2, mode = "wireguard", host = "192.0.2.1", port = 1080, name = "Relay Fork preview",
            issuedAt = 0,
            wg = WgParams("hSDwCYkwp1R0i33ctD73Wg2/Og0NOmNlA/3bdkCzNwI=", allowedIps = "0.0.0.0/0", endpointPort = 51821, dns = "1.1.1.1"),
            pairingPort = 47657, tlsFingerprint = FINGERPRINT,
        )
        private val VISUAL_HANDSHAKE = System.currentTimeMillis() / 1000
        private val PEERS = (0..2).map {
            PeerSnapshot("preview-$it", listOf("Work laptop", "Home PC", "Travel laptop")[it],
                "10.13.37.${it+2}/32", true, if (it == 2) "offline" else "connected",
                VISUAL_HANDSHAKE-120, if (it == 2) 0 else VISUAL_HANDSHAKE,
                if (it == 2) 0 else VISUAL_HANDSHAKE, 120_000 + it * 1000L, 48_000 + it * 1000L)
        }
    }
}
