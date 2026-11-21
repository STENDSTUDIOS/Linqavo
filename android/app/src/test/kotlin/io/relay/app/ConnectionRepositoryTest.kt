package io.relay.app

import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayload
import io.relay.app.service.ConnectionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRepositoryTest {

    private fun payload(host: String, port: Int = 1080) = QrPayload(
        v = 1,
        mode = QrPayload.MODE_SOCKS5,
        host = host,
        port = port,
        name = "Test Phone",
    )

    private fun advertise(code: String?) {
        ConnectionRepository.dispatch("start") { ConnectionState.Preparing }
        ConnectionRepository.dispatch("ready") {
            ConnectionState.Advertising(payload("192.168.43.1"), "FNASJQE2", code)
        }
    }

    @After
    fun tearDown() {
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
        ConnectionRepository.peers.value = emptyList()
    }

    @Test
    fun `re-advertising on a new address keeps the code on screen`() {
        // The two-digit code is drawn once and kept for the life of the
        // session, precisely so the number never changes under someone who is
        // part-way through reading it onto a keyboard. Dropping it here made
        // the phone fall back to displaying the eight-character code the
        // instant the hotspot changed address — while the PC, which had been
        // updated, was asking for two digits. The two screens disagreed and
        // there was nothing the person holding them could do about it.
        advertise("42")
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")

        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertEquals("42", state.shortCode)
        assertEquals("192.168.1.77", state.payload.host)
        assertEquals("AAA99952", state.typedCode)
    }

    @Test
    fun `re-advertising from Connected drops back to Advertising`() {
        advertise("42")
        ConnectionRepository.dispatch("clientConnected") {
            val advertising = it as ConnectionState.Advertising
            ConnectionState.Connected(advertising.payload, advertising.typedCode, advertising.shortCode, 1)
        }

        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")

        // The client count is stale after a rebind: whoever was connected is
        // connected to an address that no longer answers.
        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertEquals("42", state.shortCode)
    }

    @Test
    fun `re-advertising while idle changes nothing`() {
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")
        assertEquals(ConnectionState.Idle, ConnectionRepository.state.value)
    }

    @Test
    fun `a phone that could not announce itself still says so after a rebind`() {
        // Null is a real answer, not a missing one: it means the QR and the
        // long code are the only ways in, and the UI shows them instead.
        advertise(null)
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", null)

        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertNull(state.shortCode)
    }

    private fun peers(status: String = "connected") = (0..2).map {
        io.relay.app.core.PeerSnapshot("key-$it", "PC-$it", "10.13.37.${it+2}/32", true, status, 1, 2, 3, 100, 200)
    }

    @Test fun `one disconnect changes its card and count while authorization survives`() {
        advertise("42")
        val live = peers()
        assertEquals(3, ConnectionRepository.publishPeers(live))
        val oneOffline = live.mapIndexed { i, peer -> if (i == 0) peer.copy(status = "offline") else peer }
        assertEquals(2, ConnectionRepository.publishPeers(oneOffline))
        assertEquals(2, (ConnectionRepository.state.value as ConnectionState.Connected).clientCount)
        assertEquals(oneOffline, ConnectionRepository.peers.value)
        assertEquals(true, ConnectionRepository.peers.value.first().active)
        assertEquals(0, ConnectionRepository.publishPeers(peers("offline")))
        assertEquals(true, ConnectionRepository.state.value is ConnectionState.Advertising)
        assertEquals(3, ConnectionRepository.peers.value.size)
        assertEquals(3, ConnectionRepository.publishPeers(live))
        assertEquals(live, ConnectionRepository.peers.value)
    }

    @Test fun `reconnecting and unknown never count as connected or erase authorizations`() {
        advertise("42")
        for (status in listOf("reconnecting", "unknown", "authorized", "offline", "disabled")) {
            assertEquals(0, ConnectionRepository.publishPeers(peers(status)))
            assertEquals(3, ConnectionRepository.peers.value.size)
        }
        assertEquals(0, ConnectionRepository.publishPeers(peers().map { it.copy(active = false) }))
    }

    @Test fun `same count after reissue restores live aggregate and stopped server ignores late snapshots`() {
        advertise("42")
        val live = peers()
        ConnectionRepository.publishPeers(live)
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")
        ConnectionRepository.publishPeers(live)
        assertEquals(3, (ConnectionRepository.state.value as ConnectionState.Connected).clientCount)
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
        assertEquals(0, ConnectionRepository.publishPeers(live))
        assertEquals(ConnectionState.Idle, ConnectionRepository.state.value)
    }
}
