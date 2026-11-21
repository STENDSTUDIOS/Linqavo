package io.relay.app.e2e

import io.relay.app.core.QrPayload
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Test-only client: its pin comes directly from the app fixture, never from a network probe. */
object PinnedEnrollmentTestClient {
    fun open(payload: QrPayload, host: String = payload.host): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val pin = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (pin != payload.tlsFingerprint) throw CertificateException("fixture pin mismatch")
                }
            }), null)
        }
        val socket = context.socketFactory.createSocket(host, requireNotNull(payload.pairingPort)) as SSLSocket
        try { socket.soTimeout = 30_000; socket.startHandshake(); return socket }
        catch (error: Throwable) { socket.close(); throw error }
    }
}
