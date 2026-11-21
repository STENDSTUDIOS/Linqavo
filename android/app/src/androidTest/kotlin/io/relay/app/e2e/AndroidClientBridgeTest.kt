package io.relay.app.e2e

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wireguard.crypto.KeyPair
import io.relay.app.client.ClientBridge
import io.relay.app.client.ClientEnrollment
import io.relay.app.client.ClientIdentity
import io.relay.app.client.ClientLease
import io.relay.app.client.ClientTarget
import io.relay.app.client.ClientVpnService
import io.relay.app.client.EnrollmentError
import io.relay.app.client.EnrollmentResult
import io.relay.app.net.ClientGate
import io.relay.app.net.PairingServer
import io.relay.app.core.WgConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidClientBridgeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun vpnServiceIsSystemProtectedAndOptsOutOfAlwaysOn() {
        val component = Intent(context, ClientVpnService::class.java).component!!
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_VPN_SERVICE", service.permission)
        assertFalse(service.metaData.getBoolean("android.net.VpnService.SUPPORTS_ALWAYS_ON", true))
    }

    @Test fun nativeTunnelRefusesUnprotectedTransportAndClosesItsOwnDescriptor() {
        val address = "10.13.37.2"
        val lease = ClientLease(address, KeyPair().publicKey.toBase64(), 51821)
        val ipc = lease.ipc(KeyPair().privateKey.toBase64(), "127.0.0.1")
        val bridge = ClientBridge()
        val (owner, kernel) = ParcelFileDescriptor.createSocketPair()
        owner.use {
            kernel.use {
                var attempted = 0
                val failure = runCatching {
                    bridge.start(owner, ipc, address, { attempted++; false }, { false }, {}).close()
                }
                assertTrue(failure.isFailure)
                assertTrue(attempted > 0)
                bridge.start(owner, ipc, address, { true }, { false }, {}).use { worker ->
                    assertTrue(worker.running())
                }
                // Worker teardown must leave the service's descriptor alive so
                // it can rebind transport without dropping system routes.
                ParcelFileDescriptor.AutoCloseOutputStream(kernel.dup()).use { it.write(42) }
                ParcelFileDescriptor.AutoCloseInputStream(owner.dup()).use { assertEquals(42, it.read()) }
            }
        }
    }

    @Test fun keystoreIdentitySurvivesReopeningAndRotatesOnlyAfterRevocation() {
        val pin = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val target = ClientTarget("127.0.0.1", 47657, pin)
        val store = ClientIdentity(context)
        try {
            val first = store.keyPair(target).publicKey.toBase64()
            assertEquals(first, ClientIdentity(context).keyPair(target).publicKey.toBase64())
            store.revoke(target)
            assertNotEquals(first, ClientIdentity(context).keyPair(target).publicKey.toBase64())
        } finally {
            store.revoke(target)
        }
    }

    @Test fun productionClientPinsBeforeEnrollmentAndReusesApproval() = runBlocking {
        val gate = ClientGate()
        val keys = WgConfig.KeySet("", KeyPair().publicKey.toBase64(), "", "")
        val server = PairingServer(0, gate, {
            PairingServer.Configuration("127.0.0.1", keys.endpointPort, WgConfig.toWgParams(keys))
        }, enroll = { _, _ -> "10.13.37.3/32" })
        server.start()
        try {
            val target = ClientTarget("127.0.0.1", server.boundPort, server.fingerprint)
            val publicKey = KeyPair().publicKey.toBase64()
            fun request(to: ClientTarget) = Socket().use {
                ClientEnrollment.fetch(to, it, publicKey, "Tablet") {}
            }
            val wrong = async(Dispatchers.IO) { request(target.copy(pin = "0".repeat(64))) }.await()
            assertEquals(EnrollmentResult.Rejected(EnrollmentError.IdentityChanged), wrong)
            assertNull(gate.pending.value)
            val first = async(Dispatchers.IO) { request(target) }
            val pending = withTimeout(8_000) { gate.pending.filterNotNull().first() }
            gate.resolve(pending.address, true)
            val approved = first.await() as EnrollmentResult.Granted
            assertEquals("10.13.37.3", approved.lease.address)
            assertEquals(approved, async(Dispatchers.IO) { request(target) }.await())
            assertNull(gate.pending.value)
        } finally {
            server.stop()
        }
    }
}
