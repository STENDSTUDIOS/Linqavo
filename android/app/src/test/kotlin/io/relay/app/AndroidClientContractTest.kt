package io.relay.app

import com.wireguard.crypto.KeyPair
import io.relay.app.client.ClientEnrollment
import io.relay.app.client.ClientTarget
import io.relay.app.client.EnrollmentError
import io.relay.app.client.EnrollmentResult
import io.relay.app.client.PresenceSignal
import io.relay.app.client.fresh
import io.relay.app.client.parseOffer
import io.relay.app.client.sameIPv4Subnet
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.WgParams
import io.relay.app.net.PairingServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class AndroidClientContractTest {
    private val server = KeyPair().publicKey.toBase64()
    private val target = ClientTarget("192.168.4.1", 47657, "a".repeat(64), serverKey = server)
    private val params = WgParams(server, allowedIps = "0.0.0.0/0", endpointPort = 51821, dns = "1.1.1.1")

    @Test fun invitationAndLeaseUseCurrentContracts() {
        val qr = QrPayload(2, "wireguard", target.host, params.endpointPort, wg = params,
            pairingPort = target.port, tlsFingerprint = target.pin)
        assertEquals(target, ClientTarget.fromInvitation(QrPayloadCodec.encodeForQr(qr)))
        assertNull(ClientTarget.fromInvitation(QrPayloadCodec.encodeForQr(qr.copy(v = 1))))
        val config = PairingServer.Configuration(target.host, params.endpointPort, params)
        for (i in 2..4) {
            val reply = PairingServer.accepted(config.copy(wg = params.copy(clientAddress = "10.13.37.$i/32")))
            val result = ClientEnrollment.parse(reply, target) as EnrollmentResult.Granted
            assertEquals("10.13.37.$i", result.lease.address)
            val ipc = result.lease.ipc(KeyPair().privateKey.toBase64(), target.host)
            assertTrue(ipc.contains("allowed_ip=0.0.0.0/0\n"))
            assertFalse(ipc.contains("::/0"))
            assertTrue(ipc.contains("endpoint=192.168.4.1:51821\n"))
        }
    }

    @Test fun rejectsNarrowRoutesWrongDnsAndMismatchingKeys() {
        val config = PairingServer.Configuration(target.host, params.endpointPort, params)
        val valid = PairingServer.accepted(config)
        for (modified in listOf(
            valid.replace("0.0.0.0/0", "192.168.4.0/24"),
            valid.replace("1.1.1.1", "192.168.4.1"),
            valid.replace("10.13.37.2/32", "10.13.37.5/32"),
            valid.replace("\"wg\":{", "\"wg\":{\"clientPrivateKey\":\"forbidden\","),
        )) {
            assertEquals(EnrollmentResult.Rejected(EnrollmentError.Invalid), ClientEnrollment.parse(modified, target))
        }
        assertEquals(EnrollmentResult.Rejected(EnrollmentError.IdentityChanged),
            ClientEnrollment.parse(valid, target.copy(serverKey = KeyPair().publicKey.toBase64())))
    }

    @Test fun enrollmentDistinguishesDisabledFromRevoked() {
        assertEquals(EnrollmentResult.Rejected(EnrollmentError.Disabled),
            ClientEnrollment.parse(PairingServer.error("ERR_CLIENT_DISABLED"), target))
        assertEquals(EnrollmentResult.Rejected(EnrollmentError.Revoked),
            ClientEnrollment.parse(PairingServer.error("ERR_ENROLLMENT_REVOKED"), target))
    }

    @Test fun discoveryHintsNeverCarryTrustAndMustMatchTheirSource() {
        val message = """{"v":2,"code":"42","mode":"wireguard","host":"192.168.4.1","port":51821,"state":"sharing","pairingPort":47657,"tlsFingerprint":"${target.pin}","name":"Phone"}"""
        val offer = requireNotNull(parseOffer(message, target.host))
        assertEquals("42", offer.code)
        assertEquals(47657, offer.port)
        assertNull(parseOffer(message, "192.168.4.2"))
        assertNull(parseOffer(message.replace("sharing", "stopped"), target.host))
        assertFalse(target.copy(pin = "42").valid())
    }

    @Test fun lifecycleEventsSurviveSecondaryControlFailures() {
        for (signal in listOf("server-stopped", "client-disabled", "enrollment-revoked")) {
            val monitor = PresenceSignal()
            monitor.accept("back")
            monitor.accept(signal)
            monitor.accept("unavailable")
            assertEquals(signal, monitor.get())
        }
        val monitor = PresenceSignal()
        monitor.accept("back")
        monitor.accept("unavailable")
        assertTrue(fresh(33_000, 30_000))
        assertFalse(fresh(55_000, 30_000))
        assertFalse(fresh(1000, 0))
    }

    @Test fun reconnectRequiresAnOnLinkIpv4Path() {
        val local = InetAddress.getByName("192.168.4.105")
        assertTrue(sameIPv4Subnet(local, InetAddress.getByName("192.168.4.1"), 24))
        assertFalse(sameIPv4Subnet(local, InetAddress.getByName("192.168.5.1"), 24))
        assertFalse(sameIPv4Subnet(local, InetAddress.getByName("192.168.4.1"), 26))
        assertFalse(sameIPv4Subnet(local, InetAddress.getByName("fe80::1"), 24))
        assertFalse(sameIPv4Subnet(local, InetAddress.getByName("192.168.4.1"), 0))
    }
}
