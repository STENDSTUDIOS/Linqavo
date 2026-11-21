package io.relay.app.core

/** A confirmation belongs to an immutable enrollment and this server session. */
data class PeerActionRequest(
    val publicKey: String,
    val operation: String,
    val serverFingerprint: String,
    val enrolledAt: Long,
    val expectedActive: Boolean,
) {
    fun matches(peers: List<PeerSnapshot>, fingerprint: String?): Boolean =
        serverFingerprint.isNotEmpty() && serverFingerprint == fingerprint &&
            operation in setOf("activate", "disable", "revoke") &&
            (operation == "revoke" || expectedActive == (operation == "disable")) &&
            peers.any { it.publicKey == publicKey && it.enrolledAt == enrolledAt && it.active == expectedActive }
}

data class PeerActionResult(val request: PeerActionRequest, val name: String, val success: Boolean)
