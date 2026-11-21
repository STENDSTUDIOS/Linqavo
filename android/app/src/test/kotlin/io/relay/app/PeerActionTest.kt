package io.relay.app

import io.relay.app.core.PeerActionRequest
import io.relay.app.core.PeerSnapshot
import org.junit.Assert.*
import org.junit.Test

class PeerActionTest {
    private val peer = PeerSnapshot("identity", "PC", "10.13.37.2/32", true, "connected", 42, 0, 0, 0, 0)
    private val request = PeerActionRequest(peer.publicKey, "revoke", "server", peer.enrolledAt, true)

    @Test fun immutableEnrollmentSurvivesReorderingAndLivenessChanges() {
        assertTrue(request.matches(listOf(peer.copy(publicKey = "other"), peer.copy(status = "offline")), "server"))
    }
    @Test fun removedReenrolledOrChangedDeviceIsRejected() {
        assertFalse(request.matches(emptyList(), "server"))
        assertFalse(request.matches(listOf(peer.copy(enrolledAt = 43)), "server"))
        assertFalse(request.matches(listOf(peer.copy(active = false)), "server"))
        assertFalse(request.matches(listOf(peer.copy(publicKey = "other")), "server"))
    }
    @Test fun oldDialogCannotOperateOnRestartedServer() {
        assertFalse(request.matches(listOf(peer), "new-server"))
        assertFalse(request.matches(listOf(peer), null))
        assertFalse(request.copy(serverFingerprint = "").matches(listOf(peer), ""))
    }
    @Test fun onlyValidOperationsAndExpectedAccessTransitionsAreAccepted() {
        assertFalse(request.copy(operation = "disconnect-unauthenticated").matches(listOf(peer), "server"))
        assertFalse(request.copy(operation = "activate").matches(listOf(peer), "server"))
        assertTrue(request.copy(operation = "disable").matches(listOf(peer), "server"))
        assertTrue(request.copy(operation = "activate", expectedActive = false).matches(listOf(peer.copy(active = false)), "server"))
    }
}
