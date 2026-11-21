package io.relay.app.client

import io.relay.app.core.BoundedJson
import io.relay.app.core.DecodeResult
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.WgConfig
import io.relay.app.net.PairingServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal fun numericIPv4(text: String): Boolean =
    text.split('.').let { parts ->
        parts.size == 4 && parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 && it.toString() == part } == true
        }
    }

internal fun displayName(text: String): String = text.filter {
    !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()
}.trim().take(32)

@Serializable
internal data class ClientTarget(
    val host: String,
    val port: Int,
    val pin: String,
    val name: String = "",
    val serverKey: String? = null,
) {
    fun valid(): Boolean = numericIPv4(host) && port in 1..65535 && pin.matches(Regex("[a-f0-9]{64}"))

    companion object {
        fun fromInvitation(text: String): ClientTarget? {
            val invitation = (QrPayloadCodec.decode(text.trim()) as? DecodeResult.Ok)?.payload ?: return null
            if (invitation.v != PairingServer.VERSION) return null
            return ClientTarget(
                invitation.host, invitation.pairingPort ?: return null,
                invitation.tlsFingerprint ?: return null, displayName(invitation.name.orEmpty()),
                invitation.wg?.serverPublicKey ?: return null,
            ).takeIf { it.valid() }
        }
    }
}

internal data class ClientLease(val address: String, val serverKey: String, val endpointPort: Int) {
    fun ipc(privateKey: String, host: String): String = buildString {
        require(numericIPv4(host))
        appendLine("private_key=${WgConfig.toHex(privateKey)}")
        appendLine("public_key=${WgConfig.toHex(serverKey)}")
        appendLine("endpoint=$host:$endpointPort")
        appendLine("allowed_ip=${WgConfig.CLIENT_ALLOWED_IPS}")
        appendLine("persistent_keepalive_interval=25")
    }
}

internal enum class EnrollmentError { IdentityChanged, Denied, Disabled, Revoked, Capacity, Unavailable, Invalid }
internal sealed interface EnrollmentResult {
    data class Granted(val lease: ClientLease) : EnrollmentResult
    data class Rejected(val error: EnrollmentError) : EnrollmentResult
}

internal object ClientEnrollment {
    fun fetch(
        target: ClientTarget,
        socket: Socket,
        publicKey: String,
        name: String,
        waiting: () -> Unit,
    ): EnrollmentResult {
        var mismatch = false
        try {
            require(target.valid())
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException()
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val peer = chain.firstOrNull()
                    if (peer == null || sha256(peer.encoded) != target.pin) {
                        mismatch = true
                        throw CertificateException()
                    }
                    peer.checkValidity()
                }
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            socket.connect(InetSocketAddress(target.host, target.port), 5_000)
            val tls = context.socketFactory.createSocket(socket, target.host, target.port, true) as SSLSocket
            tls.use {
                it.soTimeout = 5_000
                it.enabledProtocols = it.supportedProtocols.filter { p -> p == "TLSv1.2" || p == "TLSv1.3" }.toTypedArray()
                it.startHandshake()
                val request = buildJsonObject {
                    put("v", PairingServer.VERSION)
                    put("pair", 1)
                    put("name", displayName(name))
                    put("clientPublicKey", publicKey)
                }
                waiting()
                it.soTimeout = 70_000
                it.getOutputStream().apply { write("$request\n".toByteArray(Charsets.UTF_8)); flush() }
                return parse(PairingServer.readBoundedLine(it.getInputStream(), PairingServer.MAX_LINE), target)
            }
        } catch (_: Exception) {
            return EnrollmentResult.Rejected(if (mismatch) EnrollmentError.IdentityChanged else EnrollmentError.Unavailable)
        }
    }

    internal fun parse(line: String?, target: ClientTarget): EnrollmentResult {
        val invalid = EnrollmentResult.Rejected(EnrollmentError.Invalid)
        if (line == null || !BoundedJson.shallow(line)) return invalid
        return try {
            val obj = Json.parseToJsonElement(line).jsonObject
            if (obj.getValue("v").jsonPrimitive.intOrNull != PairingServer.VERSION) return invalid
            obj["error"]?.let {
                return EnrollmentResult.Rejected(when (it.jsonPrimitive.content) {
                    "ERR_CLIENT_DISABLED" -> EnrollmentError.Disabled
                    "ERR_ENROLLMENT_REVOKED" -> EnrollmentError.Revoked
                    "ERR_PAIRING_DENIED" -> EnrollmentError.Denied
                    "ERR_CAPACITY_REACHED" -> EnrollmentError.Capacity
                    "ERR_FORWARDING_UNAVAILABLE", "ERR_PEER_BUSY" -> EnrollmentError.Unavailable
                    else -> EnrollmentError.Invalid
                })
            }
            if (obj.getValue("ok").jsonPrimitive.intOrNull != 1 ||
                !numericIPv4(obj.getValue("host").jsonPrimitive.content) ||
                obj.getValue("port").jsonPrimitive.intOrNull !in 1..65535
            ) return invalid
            val wg = obj.getValue("wg").jsonObject
            if ("clientPrivateKey" in wg ||
                wg.getValue("allowedIps").jsonPrimitive.content != WgConfig.CLIENT_ALLOWED_IPS ||
                wg.getValue("dns").jsonPrimitive.content != WgConfig.DEFAULT_DNS
            ) return invalid
            val address = wg.getValue("clientAddress").jsonPrimitive.content
            val key = wg.getValue("serverPublicKey").jsonPrimitive.content
            val port = wg.getValue("endpointPort").jsonPrimitive.intOrNull ?: return invalid
            if (address !in setOf("10.13.37.2/32", "10.13.37.3/32", "10.13.37.4/32") ||
                port !in 1..65535 || WgConfig.toHex(key).all { it == '0' }
            ) return invalid
            if (target.serverKey != null && target.serverKey != key) {
                return EnrollmentResult.Rejected(EnrollmentError.IdentityChanged)
            }
            EnrollmentResult.Granted(ClientLease(address.substringBefore('/'), key, port))
        } catch (_: Exception) {
            invalid
        }
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
