package io.relay.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service lifetime lock, including starts from shortcuts and sticky restarts. */
internal object RelayRole {
    enum class Role { Sharing, Client }
    private val owner = MutableStateFlow<Role?>(null)
    val active = owner.asStateFlow()

    fun acquire(role: Role): Boolean = owner.compareAndSet(null, role)
    fun release(role: Role) { owner.compareAndSet(role, null) }
}
