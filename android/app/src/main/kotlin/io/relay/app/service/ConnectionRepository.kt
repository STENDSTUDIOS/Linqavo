package io.relay.app.service

import io.relay.app.core.ConnectionRules
import io.relay.app.core.ConnectionState
import io.relay.app.core.WarningCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single observable source of the connection state, written by
 * [SharingService], read by the UI. Every write goes through the shared
 * transition table — an illegal event is dropped, never applied.
 */
object ConnectionRepository {
    val peers = MutableStateFlow<List<io.relay.app.core.PeerSnapshot>>(emptyList())
    val enrollmentFingerprint = MutableStateFlow<String?>(null)
    val managementSession = MutableStateFlow<String?>(null)
    val pendingPeerActions = MutableStateFlow<Set<String>>(emptySet())
    val peerActionResult = MutableStateFlow<io.relay.app.core.PeerActionResult?>(null)

    @Synchronized
    fun beginPeerAction(key: String): Boolean {
        if (key in pendingPeerActions.value || pendingPeerActions.value.size >= 3) return false
        pendingPeerActions.value += key
        return true
    }

    @Synchronized
    fun finishPeerAction(result: io.relay.app.core.PeerActionResult) {
        pendingPeerActions.value -= result.request.publicKey
        peerActionResult.value = result
    }
    /**
     * Who is allowed to use the proxy. Lives here rather than in the service
     * because the answer comes from the UI and the question comes from the
     * network, and this is the one object both of them already hold.
     */
    val clientGate = io.relay.app.net.ClientGate()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Non-blocking advisories shown as dismissible banners (docs/errors.md → Warning). */
    private val _warnings = MutableStateFlow<Set<WarningCode>>(emptySet())
    val warnings: StateFlow<Set<WarningCode>> = _warnings.asStateFlow()

    fun setWarning(code: WarningCode, active: Boolean) {
        _warnings.update { if (active) it + code else it - code }
    }

    fun clearWarnings() {
        _warnings.value = emptySet()
    }

    /** Cards and aggregate count follow the same fresh native snapshot. */
    @Synchronized
    fun publishPeers(snapshot: List<io.relay.app.core.PeerSnapshot>): Int {
        val current = _state.value
        if (current !is ConnectionState.Advertising && current !is ConnectionState.Connected) return 0
        peers.value = snapshot
        val connected = snapshot.count { it.active && (it.status == "connected" || it.status == "degraded") }
        if (connected > 0) {
            dispatch("clientConnected") { state ->
                val waiting = state as ConnectionState.Advertising
                ConnectionState.Connected(waiting.payload, waiting.typedCode, waiting.shortCode, connected)
            }
            dispatch("clientCountChanged") { (it as ConnectionState.Connected).copy(clientCount = connected) }
        } else {
            dispatch("lastClientDisconnected") { state ->
                val live = state as ConnectionState.Connected
                ConnectionState.Advertising(live.payload, live.typedCode, live.shortCode)
            }
        }
        return connected
    }

    /**
     * Sets the "Reconnecting…" annotation in place. This is a presentation flag,
     * not a state transition (ADR-0007): the state name is unchanged, so it does
     * not go through the transition table.
     */
    @Synchronized
    fun annotateReconnecting(active: Boolean) {
        _state.update { current ->
            when (current) {
                is ConnectionState.Connected -> current.copy(reconnecting = active)
                is ConnectionState.Advertising -> current.copy(reconnecting = active)
                else -> current
            }
        }
    }

    /**
     * After a reconnect that rebinds on a new hotspot IP, the old QR/code and any
     * client count are stale: return to Advertising with the fresh payload.
     * Only valid while sharing (Advertising/Connected); ignored otherwise.
     *
     * [shortCode] has to be carried through. It is the same code for the life of
     * the session — the whole point of drawing it once is that the number on
     * screen never changes under someone who is reading it — and dropping it
     * here made the phone fall back to displaying the eight-character code the
     * moment the hotspot changed address, while the PC was still being told to
     * ask for two digits.
     */
    @Synchronized
    fun reissue(payload: io.relay.app.core.QrPayload, typedCode: String?, shortCode: String?) {
        _state.update { current ->
            if (current is ConnectionState.Advertising || current is ConnectionState.Connected) {
                ConnectionState.Advertising(payload, typedCode, shortCode)
            } else {
                current
            }
        }
    }

    /**
     * Applies [event]; [build] produces the new state, whose name must equal
     * the table's target. Returns false when the event is illegal here.
     */
    @Synchronized
    fun dispatch(event: String, build: (ConnectionState) -> ConnectionState): Boolean {
        val current = _state.value
        val target = ConnectionRules.target(current.stateName, event) ?: return false
        val next = build(current)
        check(next.stateName == target) {
            "Event '$event' from '${current.stateName}' must produce '$target', got '${next.stateName}'"
        }
        _state.value = next
        return true
    }
}
