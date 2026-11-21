package io.relay.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.client.ClientStage
import io.relay.app.client.ClientVpnService
import io.relay.app.service.RelayRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.io.DataInputStream

/** Requires the local host WireGuard and pinned TLS fixture described in the report. */
@HostHarness
@RunWith(AndroidJUnit4::class)
class AndroidClientTunnelJourneyTest {
    @get:Rule(order = 0) val onboarding = SkipOnboarding()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemVpnConsentFullTunnelAndSameIdentityReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("relayClientHost"))
        val port = requireNotNull(args.getString("relayClientPort"))
        val pin = requireNotNull(args.getString("relayClientPin"))
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val automation = instrumentation.uiAutomation
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an isolated emulator" }

        fun click(label: Int) = compose.onNodeWithText(context.getString(label)).performScrollTo().performClick()
        fun field(label: Int, value: String) =
            compose.onNode(hasSetTextAction() and hasText(context.getString(label)))
                .performScrollTo().performTextReplacement(value)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        fun connected() {
            compose.waitUntil(35_000) {
                ClientVpnService.state.value.let { it.stage == ClientStage.Connected && it.controlAvailable }
            }
            val network = requireNotNull(manager.activeNetwork)
            assertTrue(manager.getNetworkCapabilities(network)!!.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            val link = manager.getLinkProperties(network)!!
            assertTrue(link.linkAddresses.any { it.address.hostAddress == "10.13.37.2" && it.prefixLength == 32 })
            assertTrue(link.routes.any { it.destination.toString() == "0.0.0.0/0" })
            assertTrue(link.dnsServers.any { it.hostAddress == "1.1.1.1" })
            assertFalse(link.linkAddresses.any { it.address.address.size == 16 })
        }
        fun disconnect() {
            click(R.string.client_mode_disconnect)
            compose.waitUntil(10_000) {
                RelayRole.active.value == null && manager.getNetworkCapabilities(manager.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
            }
        }
        fun wifi(enabled: Boolean) {
            shell("svc wifi ${if (enabled) "enable" else "disable"}")
        }
        click(R.string.client_mode_title)
        field(R.string.client_host, host)
        field(R.string.client_port, port)
        field(R.string.client_fingerprint, pin)
        try {
            val consent = requireNotNull(VpnService.prepare(context)) { "Clear the emulator's ACTIVATE_VPN approval first" }
            // Inject the system cancellation result instead of coupling this
            // test to the VPN dialog's accessibility tree and button layout.
            val denied = instrumentation.addMonitor(requireNotNull(consent.component).className,
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                click(R.string.client_connect_action)
            } finally {
                instrumentation.removeMonitor(denied)
            }
            assertEquals(1, denied.hits)
            compose.onNodeWithText(context.getString(R.string.client_vpn_denied)).assertExists()
            assertNull(RelayRole.active.value)
            assertNotNull(VpnService.prepare(context))
            shell("cmd appops set ${context.packageName} ACTIVATE_VPN allow")
            assertNull(VpnService.prepare(context))
            click(R.string.client_connect_action)
            connected()
            val message = "relay through Android TUN".encodeToByteArray()
            Socket().use {
                it.connect(InetSocketAddress("1.1.1.1", 41820), 5_000)
                it.soTimeout = 5_000
                it.getOutputStream().write(message)
                val reply = ByteArray(message.size)
                DataInputStream(it.getInputStream()).readFully(reply)
                assertArrayEquals(message, reply)
            }
            DatagramSocket().use {
                it.connect(InetSocketAddress("1.1.1.1", 41820))
                it.soTimeout = 5_000
                it.send(DatagramPacket(message, message.size))
                val reply = DatagramPacket(ByteArray(128), 128)
                it.receive(reply)
                assertArrayEquals(message, reply.data.copyOf(reply.length))
            }
            DatagramSocket().use {
                val rootNs = byteArrayOf(17, 39, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)
                it.connect(InetSocketAddress("1.1.1.1", 53))
                it.soTimeout = 5_000
                it.send(DatagramPacket(rootNs, rootNs.size))
                val reply = DatagramPacket(ByteArray(512), 512)
                it.receive(reply)
                assertTrue(reply.length >= 12)
                assertEquals(17.toByte(), reply.data[0])
                assertEquals(39.toByte(), reply.data[1])
                assertEquals(0x80, reply.data[2].toInt() and 0x80)
            }
            wifi(false)
            try {
                compose.waitUntil(15_000) {
                    ClientVpnService.state.value.stage in setOf(ClientStage.Reconnecting, ClientStage.Offline)
                }
                assertEquals(RelayRole.Role.Client, RelayRole.active.value)
                val attempt = runCatching {
                    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 41820), 2_000) }
                }
                assertTrue("The lost link must not yield a direct connection", attempt.isFailure)
            } finally {
                wifi(true)
            }
            connected()
            disconnect()
            click(R.string.client_connect_action)
            connected()
            fun fixtureCommand(command: Int) {
                Socket().use {
                    it.connect(InetSocketAddress("1.1.1.1", 41821), 5_000)
                    it.getOutputStream().write(command)
                }
            }
            fixtureCommand('F'.code)
            compose.waitUntil(45_000) {
                ClientVpnService.state.value.let { it.stage == ClientStage.Offline && it.controlAvailable }
            }
            assertTrue(runCatching {
                Socket().use { it.connect(InetSocketAddress("1.1.1.1", 41820), 2_000) }
            }.isFailure)
            fixtureCommand('S'.code)
            compose.waitUntil(5_000) {
                ClientVpnService.state.value.let {
                    it.stage == ClientStage.Offline && !it.controlAvailable && it.detail == R.string.client_server_stopped
                }
            }
            assertTrue(manager.getNetworkCapabilities(manager.activeNetwork)!!.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            disconnect()
        } finally {
            if (RelayRole.active.value == RelayRole.Role.Client) ClientVpnService.disconnect(context)
        }
    }
}
