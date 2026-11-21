package io.relay.app.core

import kotlinx.serialization.Serializable

/** Public per-peer state from the native registry. No private keys or enrollment secrets. */
@Serializable
data class PeerSnapshot(
    val publicKey: String,
    val name: String,
    val address: String,
    val active: Boolean,
    val status: String,
    val enrolledAt: Long,
    val lastHandshake: Long,
    val lastActivity: Long,
    val bytesReceived: Long,
    val bytesSent: Long,
    val forwardingAgeMs: Long = -1,
    val controlState: String = "unknown",
)
