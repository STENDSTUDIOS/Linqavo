package io.relay.app.client

import android.os.ParcelFileDescriptor
import java.lang.reflect.Proxy

/** Match the server's optional native bridge so JVM-only builds still compile. */
internal class ClientBridge {
    private val library = Class.forName("linqavowg.Linqavowg")
    private val callbacks = Class.forName("linqavowg.AndroidClientSockets")
    private val start = library.getMethod("startAndroidClient", Int::class.javaPrimitiveType,
        String::class.java, String::class.java, callbacks)

    fun start(
        tun: ParcelFileDescriptor,
        config: String,
        address: String,
        protect: (Int) -> Boolean,
        bindPresence: (Int) -> Boolean,
        event: (String) -> Unit,
    ): ClientEngine {
        val bridge = Proxy.newProxyInstance(callbacks.classLoader, arrayOf(callbacks)) { proxy, method, args ->
            when (method.name) {
                "prepareTransport" -> protect(args!![0] as Int)
                "preparePresence" -> bindPresence(args!![0] as Int)
                "onPresence" -> { event(args!![0] as String); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "Linqavo Android client sockets"
                else -> null
            }
        }
        val fd = ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        return ClientEngine(checkNotNull(start.invoke(null, fd, config, address, bridge)))
    }
}

internal class ClientEngine(private val handle: Any) : AutoCloseable {
    private val close = handle.javaClass.getMethod("close")
    private val running = handle.javaClass.getMethod("running")
    private val received = handle.javaClass.getMethod("received")
    private val sent = handle.javaClass.getMethod("sent")
    private val replies = handle.javaClass.getMethod("replies")
    private val handshakeAge = handle.javaClass.getMethod("handshakeAgeSeconds")

    fun running() = running.invoke(handle) as Boolean
    fun received() = received.invoke(handle) as Long
    fun sent() = sent.invoke(handle) as Long
    fun replies() = replies.invoke(handle) as Long
    fun handshakeAge() = handshakeAge.invoke(handle) as Long
    override fun close() { close.invoke(handle) }
}
