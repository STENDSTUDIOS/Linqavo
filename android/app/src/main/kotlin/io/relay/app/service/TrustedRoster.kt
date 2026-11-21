package io.relay.app.service

import kotlinx.serialization.Serializable

@Serializable
internal data class TrustedRoster(
    val devices: List<TrustedDevice> = emptyList(),
    val revoked: List<String> = emptyList(),
) {
    init {
        require(devices.size <= 3)
        require(devices.distinctBy { it.publicKey }.size == devices.size)
        require(devices.none { it.publicKey in revoked })
    }

    fun remember(key: String, name: String): TrustedRoster =
        if (devices.size == 3 || devices.any { it.publicKey == key } || key in revoked) this
        else copy(devices = devices + TrustedDevice(key, name))

    fun setEnabled(key: String, enabled: Boolean) =
        copy(devices = devices.map { if (it.publicKey == key) it.copy(enabled = enabled) else it })

    fun forget(key: String) = copy(devices = devices.filterNot { it.publicKey == key })

    fun revoke(key: String) = forget(key).copy(revoked = (revoked - key) + key)
}
