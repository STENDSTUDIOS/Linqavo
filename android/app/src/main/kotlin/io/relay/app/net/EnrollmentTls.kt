package io.relay.app.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** A stable phone identity lets returning clients keep their per-phone keys. */
class EnrollmentTls {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val context: SSLContext
    val fingerprint: String

    init {
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Linqavo"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(4102444800000L))
                .build())
            generator.generateKeyPair()
        }
        val certificate = store.getCertificate(ALIAS) as X509Certificate
        val signingKey = store.getKey(ALIAS, null) as PrivateKey
        fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val manager = object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
                ALIAS.takeIf { type == "EC" }
            override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) =
                ALIAS.takeIf { type == "EC" }
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                arrayOf(ALIAS).takeIf { type == "EC" }
            override fun getCertificateChain(alias: String?) = arrayOf(certificate).takeIf { alias == ALIAS }
            override fun getPrivateKey(alias: String?) = signingKey.takeIf { alias == ALIAS }
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        context = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
    }

    private companion object { const val ALIAS = "linqavo-server-enrollment-v1" }
}
