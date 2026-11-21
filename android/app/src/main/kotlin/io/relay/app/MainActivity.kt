package io.relay.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import io.relay.app.ui.OnboardingScreen
import io.relay.app.ui.OnboardingStep
import io.relay.app.ui.StepAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import io.relay.app.service.ConnectionRepository
import io.relay.app.core.ConnectionState
import io.relay.app.service.HomeScreenExtras
import io.relay.app.service.LocalNetworkAccess
import io.relay.app.core.LocalNetworkPermission
import io.relay.app.service.Settings as RelaySettings
import io.relay.app.net.VpnLockdown
import io.relay.app.service.DiagnosticReport
import io.relay.app.ui.HomeScreen
import io.relay.app.ui.ClientScreen
import io.relay.app.client.ClientVpnService
import io.relay.app.service.RelayRole
import io.relay.app.ui.MainViewModel
import io.relay.app.ui.theme.RelayBackground
import io.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Set when the launcher shortcut brought us here, cleared once acted on.
     *
     * A flag rather than a direct call into the view model, because starting
     * has to go through the same path the button does — the notification
     * permission prompt lives in a Compose launcher, and a second way in would
     * be a second place for that to be forgotten.
     */
    private val startRequested = MutableStateFlow(false)
    private val clientRequested = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readStartRequest(intent)
        else startRequested.value = savedInstanceState.getBoolean(STATE_START_REQUESTED)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            RelayTheme(themeMode = themeMode) {
                RelayBackground {
                    val state by viewModel.state.collectAsState()
                    val role by RelayRole.active.collectAsState()
                    val wantsClient by clientRequested.collectAsState()
                    var clientMode by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(role, wantsClient) {
                        if (role == RelayRole.Role.Client || wantsClient) clientMode = true
                        clientRequested.value = false
                    }
                    val peers by viewModel.peers.collectAsState()
                    val pendingPeerActions by ConnectionRepository.pendingPeerActions.collectAsState()
                    val peerActionResult by ConnectionRepository.peerActionResult.collectAsState()
                    LaunchedEffect(peerActionResult) {
                        peerActionResult?.let { result ->
                            val message = if (!result.success) R.string.client_action_failed else when (result.request.operation) {
                                "revoke" -> R.string.client_revoked_result
                                "disable" -> R.string.client_disabled_result
                                else -> R.string.client_enabled_result
                            }
                            Toast.makeText(this@MainActivity, getString(message, result.name.ifBlank { getString(R.string.client_generic) }), Toast.LENGTH_LONG).show()
                            ConnectionRepository.peerActionResult.compareAndSet(result, null)
                        }
                    }
                    val maximumClients by viewModel.maximumClients.collectAsState()
                    val enrollmentFingerprint by ConnectionRepository.enrollmentFingerprint.collectAsState()
                    val managementSession by ConnectionRepository.managementSession.collectAsState()
                    val batteryExempt by viewModel.batteryExempt.collectAsState()
                    val warnings by viewModel.warnings.collectAsState()
                    val logs by viewModel.logs.collectAsState()

                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {
                        // Local access may have changed while the dialog was open.
                        if (LocalNetworkAccess.granted(this@MainActivity)) viewModel.startSharing()
                    } // notification denial alone does not prevent sharing
                    var localPermissionDenied by rememberSaveable { mutableStateOf(false) }
                    val startWithNotification: () -> Unit = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startSharing()
                        }
                    }
                    val localPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted && LocalNetworkAccess.granted(this@MainActivity)) {
                            startWithNotification()
                        } else {
                            localPermissionDenied = true
                        }
                    }
                    val localSettings = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) {
                        if (LocalNetworkAccess.granted(this@MainActivity)) {
                            startWithNotification()
                        } else {
                            localPermissionDenied = true
                        }
                    }
                    val requestStart: () -> Unit = {
                        if (RelayRole.active.value != RelayRole.Role.Client) {
                            clientMode = false
                            if (LocalNetworkAccess.granted(this@MainActivity)) startWithNotification()
                            else localPermission.launch(LocalNetworkPermission.NAME)
                        }
                    }
                    if (localPermissionDenied) {
                        AlertDialog(
                            onDismissRequest = { localPermissionDenied = false },
                            title = { Text(stringResource(R.string.local_network_title)) },
                            text = { Text(stringResource(R.string.local_network_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    localPermissionDenied = false
                                    localSettings.launch(Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName"),
                                    ))
                                }) { Text(stringResource(R.string.local_network_settings)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { localPermissionDenied = false }) {
                                    Text(stringResource(R.string.local_network_cancel))
                                }
                            },
                        )
                    }

                    // Re-check the exemption whenever the user returns from Settings,
                    // and watch the cable for as long as the screen is in front of
                    // someone. watchCable suspends forever, so it is cancelled on
                    // the way out of RESUMED and restarted on the way back in --
                    // which is also what makes returning from the tethering screen
                    // update the card immediately rather than up to two seconds later.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(lifecycleOwner) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            if (!LocalNetworkAccess.granted(this@MainActivity) &&
                                ConnectionRepository.state.value.let {
                                    it is ConnectionState.Preparing || it is ConnectionState.Advertising ||
                                        it is ConnectionState.Connected
                                }
                            ) {
                                viewModel.stopSharing()
                                localPermissionDenied = true
                            }
                            viewModel.checkForUpdate()
                            viewModel.refreshBatteryExempt()
                            viewModel.watchCable()
                        }
                    }

                    val pendingClient by ConnectionRepository.clientGate.pending.collectAsState()
                    val update by viewModel.availableRelease.collectAsState()
                    val updateAction: () -> Unit = {
                        val release = update
                        if (release == null) viewModel.checkForUpdate(manual = true)
                        else try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.download)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, R.string.update_browser_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    val cable by viewModel.cable.collectAsState()

                    // The launcher shortcut. Honoured only from Idle: arriving
                    // here while already sharing means the person tapped it out
                    // of habit, and restarting would rotate the keys and drop
                    // the PC that is currently connected.
                    val wantsStart by startRequested.collectAsState()
                    LaunchedEffect(wantsStart, state) {
                        if (!wantsStart) return@LaunchedEffect
                        startRequested.value = false
                        if (state is ConnectionState.Error) viewModel.dismissError()
                        else if (state !is ConnectionState.Idle) return@LaunchedEffect
                        requestStart()
                    }

                    // First launch only. Everything it offers is reachable
                    // from Advanced afterwards, so it never has to be shown twice.
                    val settings = remember { RelaySettings(this@MainActivity) }
                    var onboarding by remember { mutableStateOf(!settings.onboarded) }
                    if (onboarding) {
                        OnboardingScreen(
                            steps = onboardingSteps(batteryExempt),
                            onDone = {
                                settings.onboarded = true
                                onboarding = false
                            },
                        )
                        return@RelayBackground
                    }

                    if (clientMode && role != RelayRole.Role.Sharing) {
                        ClientScreen(themeMode, viewModel::setThemeMode, update?.version, updateAction) { clientMode = false }
                        return@RelayBackground
                    }

                    HomeScreen(
                        state = state,
                        batteryExempt = batteryExempt,
                        warnings = warnings,
                        themeMode = themeMode,
                        fullModeAvailable = viewModel.fullModeAvailable,
                        logs = logs,
                        onStart = requestStart,
                        onStop = viewModel::stopSharing,
                        onRetry = { viewModel.dismissError(); requestStart() },
                        onDismissError = viewModel::dismissError,
                        onAllowBattery = ::requestBatteryExemption,
                        onDismissWarning = viewModel::dismissWarning,
                        onSetTheme = viewModel::setThemeMode,
                        onClearLogs = viewModel::clearLogs,
                        onGetUpdate = updateAction,
                        peers = peers,
                        maximumClients = maximumClients,
                        onSetMaximumClients = viewModel::setMaximumClients,
                        onPeerAction = viewModel::peerAction,
                        pendingPeerActions = pendingPeerActions,
                        enrollmentFingerprint = enrollmentFingerprint,
                        managementSession = managementSession,
                        onShareLogs = {
                            // Built here rather than in the view model because the
                            // report needs the installed version, and the share
                            // sheet needs an Activity to launch from.
                            val version = runCatching {
                                packageManager.getPackageInfo(packageName, 0).versionName
                            }.getOrNull() ?: "unknown"
                            val report = DiagnosticReport.build(
                                state, logs,
                                appVersion = version,
                                vpnLockdown = VpnLockdown.describe(
                                    VpnLockdown.read(this@MainActivity)
                                ),
                            )
                            startActivity(DiagnosticReport.shareIntent(this@MainActivity, report))
                        },
                        updateAvailable = update?.version,
                        pendingClient = pendingClient?.address,
                        pendingClientName = pendingClient?.name,
                        onOpenNetworkSettings = ::openNetworkSettings,
                        onApproveClient = { allowed ->
                            pendingClient?.let {
                                ConnectionRepository.clientGate.resolve(it.address, allowed)
                            }
                        },
                        cable = cable,
                        onTurnOnUsb = viewModel::openTetheringSettings,
                        onDismissUsbOffer = viewModel::dismissUsbOffer,
                        onOpenVpnSettings = viewModel::openVpnSettings,
                        onConnectMode = { clientMode = true },
                    )
                }
            }
        }
    }

    /**
     * singleTop means a shortcut tap on an already-open Relay arrives here
     * rather than through onCreate, so both have to read it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readStartRequest(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // A consumed shortcut must not request permission again after Cancel
        // merely because the Activity was rotated or recreated from Settings.
        outState.putBoolean(STATE_START_REQUESTED, startRequested.value)
        super.onSaveInstanceState(outState)
    }

    private fun readStartRequest(intent: Intent?) {
        if (intent?.action == ACTION_START_SHARING) startRequested.value = true
        if (intent?.action == ClientVpnService.OPEN) clientRequested.value = true
    }

    companion object {
        private const val STATE_START_REQUESTED = "startRequested"
        /** Declared in the manifest and in res/xml/shortcuts.xml; keep the three in step. */
        const val ACTION_START_SHARING = "io.relay.fork.action.START_SHARING"
    }


    /**
     * The first-run checklist.
     *
     * Each step is a button when Android will let an app ask for the thing, and
     * a line of instructions when it will not — which is version-dependent, so
     * [HomeScreenExtras] decides and this only renders the answer. The
     * alternative is a button that does nothing on the phones where the
     * platform declines, which is worse than no button.
     */
    @androidx.compose.runtime.Composable
    private fun onboardingSteps(batteryExempt: Boolean): List<OnboardingStep> {
        val ctx = this
        // State, not a read: the step has to tick the moment Android answers.
        // It used to be read once during composition, and nothing recomposed
        // when the permission dialog closed, so "Allow notifications" stayed on
        // screen after it had been allowed (docs/ux-audit.md P-37).
        var notifGranted by remember {
            mutableStateOf(
                Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }
        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> notifGranted = granted }

        val canTile = HomeScreenExtras.canOfferTile()
        val canWidget = HomeScreenExtras.canOfferWidget(ctx)
        val widgetOn = HomeScreenExtras.widgetPlaced(ctx)
        val tileManual = stringResource(R.string.onboarding_tile_manual)
        val widgetManual = stringResource(R.string.onboarding_widget_manual)

        return listOf(
            OnboardingStep(
                title = stringResource(R.string.onboarding_notif_title),
                body = stringResource(R.string.onboarding_notif_body),
                action = if (notifGranted) StepAction.Done else StepAction.Offer,
                actionLabel = stringResource(R.string.onboarding_notif_action),
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                essential = true,
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_battery_title),
                body = stringResource(R.string.onboarding_battery_body),
                action = if (batteryExempt) StepAction.Done else StepAction.Offer,
                actionLabel = stringResource(R.string.onboarding_battery_action),
                onAction = ::requestBatteryExemption,
                essential = true,
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_tile_title),
                body = stringResource(R.string.onboarding_tile_body),
                action = if (canTile) StepAction.Offer else StepAction.Instruct,
                actionLabel = if (canTile) {
                    stringResource(R.string.onboarding_tile_action)
                } else {
                    tileManual
                },
                onAction = {
                    if (canTile) {
                        HomeScreenExtras.offerTile(ctx)
                    } else {
                        Toast.makeText(ctx, tileManual, Toast.LENGTH_LONG).show()
                    }
                },
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_widget_title),
                body = stringResource(R.string.onboarding_widget_body),
                action = when {
                    widgetOn -> StepAction.Done
                    canWidget -> StepAction.Offer
                    else -> StepAction.Instruct
                },
                actionLabel = if (canWidget) {
                    stringResource(R.string.onboarding_widget_action)
                } else {
                    widgetManual
                },
                onAction = {
                    if (canWidget) {
                        HomeScreenExtras.offerWidget(ctx)
                    } else {
                        Toast.makeText(ctx, widgetManual, Toast.LENGTH_LONG).show()
                    }
                },
            ),
            OnboardingStep(
                title = stringResource(R.string.onboarding_shortcut_title),
                body = stringResource(R.string.onboarding_shortcut_body),
                action = StepAction.Done,
                actionLabel = "",
                onAction = {},
            ),
        )
    }

    /**
     * The screen where Wi-Fi, the hotspot and USB tethering live, for the three
     * errors that only a setting can fix. "Wireless & networks" is that screen
     * on most builds; a build without it gets Settings itself, which is still
     * one tap closer than nothing.
     */
    private fun openNetworkSettings() {
        for (action in listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Exception) {
                // Not on this build; try the next.
            }
        }
    }

    /** Deep link to the exemption dialog for this app (ADR-0003). */
    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(direct)
        } catch (_: Exception) {
            // Some OEM builds block the direct dialog — fall back to the list screen.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
