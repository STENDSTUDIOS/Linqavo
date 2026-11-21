package io.relay.app.net.wg

import com.wireguard.crypto.KeyPair
import io.relay.app.core.WgConfig

/** Server-only session keys from the official WireGuard library. Clients generate their own keys. */
object WgKeys {
    fun generate(
        endpointPort: Int = WgConfig.DEFAULT_ENDPOINT_PORT,
        dns: String = WgConfig.DEFAULT_DNS,
    ): WgConfig.KeySet {
        val server = KeyPair()
        return WgConfig.KeySet(
            serverPrivateKey = server.privateKey.toBase64(),
            serverPublicKey = server.publicKey.toBase64(),
            clientPrivateKey = "",
            clientPublicKey = "",
            endpointPort = endpointPort,
            dns = dns,
        )
    }
}
