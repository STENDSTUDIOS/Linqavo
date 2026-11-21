package io.relay.app.net.wg

import java.lang.reflect.Method

/**
 * Bridges [WgForwarder] to the gomobile-built `linqavowg` AAR (`/wg`).
 *
 * Reached reflectively rather than by a compile-time reference, because the AAR
 * is produced by a Go toolchain that only runs in CI. A direct call would make
 * every developer without gomobile unable to compile the app at all, and would
 * make the Android build depend on a Go build for a mode most people never turn
 * on. [WgForwarderProvider] already looks this class up the same way, so the
 * cost is one layer, not two.
 *
 * When the AAR is absent every call throws [WgForwarderException] with a
 * message that says so, which the service turns into WG_START_FAILED — an
 * honest "this build cannot do Full Mode" rather than a silent no-op.
 */
class GoWgForwarder : WgForwarder {

    private var lastPeers = emptyList<io.relay.app.core.PeerSnapshot>()

    private val startEndpoint: Method
    private val stopEndpoint: Method
    private val isRunning: Method
    private val lastHandshakeUnix: Method
    private val bytesReceived: Method
    private val bytesSent: Method

    init {
        val linqavowg = try {
            Class.forName(NATIVE_CLASS)
        } catch (e: Throwable) {
            throw WgForwarderException(
                "Full Mode is not included in this build (the linqavowg library is missing)", e,
            )
        }
        linqavowg.getMethod("endpointPeersJSON")
        linqavowg.getMethod("peerEnrollmentError", String::class.java)
        linqavowg.getMethod("enrollEndpointPeer", String::class.java, String::class.java)
        linqavowg.getMethod("setEndpointCapacity", Long::class.javaPrimitiveType)
        linqavowg.getMethod("setEndpointPeerActive", String::class.java, Boolean::class.javaPrimitiveType)
        linqavowg.getMethod("removeEndpointPeer", String::class.java)
        startEndpoint = linqavowg.getMethod("startEndpoint", String::class.java)
        stopEndpoint = linqavowg.getMethod("stopEndpoint")
        isRunning = linqavowg.getMethod("isRunning")
        lastHandshakeUnix = linqavowg.getMethod("lastHandshakeUnix")
        bytesReceived = linqavowg.getMethod("bytesReceived")
        bytesSent = linqavowg.getMethod("bytesSent")
    }

    @Synchronized
    override fun start(config: String) {
        lastPeers = emptyList()
        try {
            startEndpoint.invoke(null, config)
        } catch (e: Throwable) {
            // Reflection wraps whatever Go returned; the cause is the message
            // worth showing, and the wrapper says nothing useful.
            throw WgForwarderException(
                "The WireGuard endpoint did not start: ${e.cause?.message ?: e.message}", e,
            )
        }
        if (!running()) {
            throw WgForwarderException("The WireGuard endpoint reported no error but is not running")
        }
    }

    override fun enrollPeer(publicKey: String, name: String): String? = runCatching {
        Class.forName(NATIVE_CLASS).getMethod("enrollEndpointPeer", String::class.java, String::class.java)
            .invoke(null, publicKey, name) as String
    }.getOrNull()
    override fun setCapacity(capacity: Int): Boolean = runCatching {
        Class.forName(NATIVE_CLASS).getMethod("setEndpointCapacity", Long::class.javaPrimitiveType).invoke(null, capacity.toLong())
    }.isSuccess
    override fun enrollmentError(publicKey: String): String = runCatching {
        Class.forName(NATIVE_CLASS).getMethod("peerEnrollmentError", String::class.java).invoke(null, publicKey) as String
    }.getOrDefault("ERR_FORWARDING_UNAVAILABLE")
    @Synchronized
    override fun peers(): List<io.relay.app.core.PeerSnapshot> {
        lastPeers = runCatching {
            val text = Class.forName(NATIVE_CLASS).getMethod("endpointPeersJSON").invoke(null) as String
            kotlinx.serialization.json.Json.decodeFromString<List<io.relay.app.core.PeerSnapshot>>(text)
        }.getOrElse { lastPeers.map { if (it.active) it.copy(status = "unknown") else it } }
        return lastPeers
    }
    override fun setPeerActive(publicKey: String, active: Boolean): Boolean = runCatching {
        Class.forName(NATIVE_CLASS).getMethod("setEndpointPeerActive", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, publicKey, active)
    }.isSuccess
    override fun removePeer(publicKey: String): Boolean = runCatching {
        Class.forName(NATIVE_CLASS).getMethod("removeEndpointPeer", String::class.java).invoke(null, publicKey)
    }.isSuccess

    @Synchronized
    override fun stop() {
        lastPeers = emptyList()
        // Deliberately swallowing: stop runs while the service is already
        // tearing down, often from a failure, and throwing here would replace
        // the real error with this one.
        val result = runCatching { stopEndpoint.invoke(null) }
        io.relay.app.service.LifecycleTrace.event("server", "local", "-", "event=native-stop " +
            (result.getOrNull() as? String ?: if (result.isFailure) "bridge-error" else "legacy"))
    }

    /** True when the Go side has a live endpoint, asked rather than remembered. */
    fun running(): Boolean = runCatching { isRunning.invoke(null) as Boolean }.getOrDefault(false)

    // Polled once a second to drive the screen. Every one of these answers 0
    // rather than throwing: a session that has just been torn down is the
    // normal case for the tick that arrives right after Stop, and an exception
    // there would turn a stale label into a crash.
    override fun lastHandshakeUnix(): Long = number(lastHandshakeUnix)

    override fun bytesReceived(): Long = number(bytesReceived)

    override fun bytesSent(): Long = number(bytesSent)

    private fun number(method: Method): Long =
        runCatching { method.invoke(null) as Long }.getOrDefault(0L)

    private companion object {
        /**
         * gomobile lowercases the package name and exposes each exported Go
         * function as a static method on a class named after the package.
         */
        const val NATIVE_CLASS = "linqavowg.Linqavowg"
    }
}
