package io.relay.app.service

import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in, bounded and separate from the regular diagnostic report. */
internal object LifecycleTrace {
    private const val TAG = "RelayLifecycle"
    private val remaining = AtomicInteger(240)
    val enabled: Boolean get() = Log.isLoggable(TAG, Log.DEBUG) && remaining.get() > 0

    fun id(publicKey: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKey))
        digest.take(6).joinToString("") { "%02x".format(it) }
    }.getOrDefault("unknown")

    fun event(role: String, session: String, peer: String, fields: String) {
        if (enabled && remaining.getAndDecrement() > 0) {
            Log.d(TAG, "role=$role session=$session peer=$peer protocol=RLYP0002 utcMs=${System.currentTimeMillis()} " +
                "monoMs=${SystemClock.elapsedRealtime()} $fields")
        }
    }
}
