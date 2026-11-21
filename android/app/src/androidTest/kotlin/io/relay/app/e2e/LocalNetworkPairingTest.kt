package io.relay.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.core.ConnectionState
import io.relay.app.net.PairingServer
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.LocalNetworkAccess
import io.relay.app.service.SharingService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real service, native endpoint and approved TCP exchange; no mocked sockets. */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPairingTest {
    @get:Rule val localNetwork = LocalNetworkGrantRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun stop() {
        SharingService.stop(context)
        await { ConnectionRepository.state.value is ConnectionState.Idle }
    }

    @Test fun grantedPermissionAllowsApprovedTcpPairingWithTheNativeEndpoint() {
        assertTrue(LocalNetworkAccess.granted(context))
        SharingService.start(context)
        await { ConnectionRepository.state.value is ConnectionState.Advertising }
        val payload = (ConnectionRepository.state.value as ConnectionState.Advertising).payload
        val executor = Executors.newSingleThreadExecutor()
        val clientKey = com.wireguard.crypto.KeyPair()
        val store = io.relay.app.service.TrustedDevices.get(context)
        try {
            val response = executor.submit<String> {
                val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                            val pin = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            if (pin != payload.tlsFingerprint) throw java.security.cert.CertificateException("pin mismatch")
                        }
                    }), null)
                }
                (tls.socketFactory.createSocket("127.0.0.1", PairingServer.DEFAULT_PORT) as javax.net.ssl.SSLSocket).use { socket ->
                    socket.startHandshake()
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write((JSONObject().put("v",2).put("pair",1).put("name","Permission regression")
                        .put("clientPublicKey",clientKey.publicKey.toBase64()).toString()+"\n").toByteArray())
                    socket.getInputStream().bufferedReader().readLine()
                }
            }
            await { ConnectionRepository.clientGate.pending.value != null }
            val pending = requireNotNull(ConnectionRepository.clientGate.pending.value)
            ConnectionRepository.clientGate.resolve(pending.address, true)
            val reply = JSONObject(response.get(15, TimeUnit.SECONDS))
            assertEquals(1, reply.getInt("ok"))
            assertEquals(payload.wg!!.serverPublicKey, reply.getJSONObject("wg").getString("serverPublicKey"))
            assertTrue(!reply.getJSONObject("wg").has("clientPrivateKey"))
            assertEquals("10.13.37.2/32", reply.getJSONObject("wg").getString("clientAddress"))
            val publicKey = clientKey.publicKey.toBase64()
            assertTrue(store.devices.value.any { it.publicKey == publicKey })
            SharingService.stop(context)
            await { ConnectionRepository.state.value is ConnectionState.Idle }
            SharingService.start(context)
            await { ConnectionRepository.state.value is ConnectionState.Advertising }
            val next = (ConnectionRepository.state.value as ConnectionState.Advertising).payload
            assertEquals(payload.tlsFingerprint, next.tlsFingerprint)
            org.junit.Assert.assertNotEquals(payload.wg!!.serverPublicKey, next.wg!!.serverPublicKey)
            val target = io.relay.app.client.ClientTarget("127.0.0.1", next.pairingPort!!, payload.tlsFingerprint!!)
            val returning = executor.submit<io.relay.app.client.EnrollmentResult> {
                Socket().use { io.relay.app.client.ClientEnrollment.fetch(target, it, publicKey, "Renamed PC") {} }
            }
            assertTrue(returning.get(10, TimeUnit.SECONDS) is io.relay.app.client.EnrollmentResult.Granted)
            org.junit.Assert.assertNull(ConnectionRepository.clientGate.pending.value)
        } finally {
            store.forget(clientKey.publicKey.toBase64())
            executor.shutdownNow()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out; state=${ConnectionRepository.state.value.stateName}", condition())
    }
}
