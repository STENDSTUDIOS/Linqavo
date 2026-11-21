package io.relay.app.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

internal class ClientIdentity(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "linqavo-client")
    private fun legacyKey(pin: String) = File(File(context.noBackupFilesDir, "relay-client"), pin)
    private val keystore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun keyPair(target: ClientTarget): KeyPair {
        require(target.valid())
        check(directory.isDirectory || directory.mkdirs())
        val legacy = legacyKey(target.pin)
        val existingLegacy = legacy.exists() || File(legacy.path + ".bak").exists()
        val file = AtomicFile(if (existingLegacy) legacy else File(directory, target.pin))
        val alias = if (existingLegacy) "relay-client-identity-v1" else KEY_ALIAS
        val aad = target.pin.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val encrypted = try {
            file.readFully()
        } catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            null
        }
        if (encrypted != null) {
            require(encrypted.size == 12 + 32 + 16)
            val key = keystore.getKey(alias, null) as SecretKey
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val raw = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            return try { KeyPair(Key.fromBytes(raw)) } finally { raw.fill(0) }
        }
        val key = keystore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        val identity = KeyPair()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val raw = identity.privateKey.bytes
        val ciphertext = try { cipher.iv + cipher.doFinal(raw) } finally { raw.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        // AtomicFile's finishWrite can report a storage failure through the
        // platform logger. Confirm durability before requesting a server slot.
        check(file.readFully().contentEquals(ciphertext))
        return identity
    }

    fun revoke(target: ClientTarget) {
        require(target.valid())
        val base = File(directory, target.pin)
        AtomicFile(legacyKey(target.pin)).delete()
        check(!legacyKey(target.pin).exists() && !File(legacyKey(target.pin).path + ".bak").exists())
        AtomicFile(base).delete()
        check(!base.exists())
    }

    companion object {
        private const val KEY_ALIAS = "linqavo-client-identity-v1"

        // This metadata contains public data only. The private key has no
        // backup or transfer path, including Android device-to-device restore.
        fun lastTarget(context: Context): ClientTarget? = runCatching {
            val current = File(context.noBackupFilesDir, "linqavo-client-target")
            val file = if (current.exists() || File(current.path + ".bak").exists()) current
                else File(context.noBackupFilesDir, "relay-client-target")
            Json.decodeFromString<ClientTarget>(AtomicFile(file).readFully().decodeToString())
                .takeIf { it.valid() }?.copy(serverKey = null) // A saved certificate authenticates the next session’s rotating tunnel key.
        }.getOrNull()

        fun remember(context: Context, target: ClientTarget) {
            val file = AtomicFile(File(context.noBackupFilesDir, "linqavo-client-target"))
            val out = file.startWrite()
            try {
                out.write(Json.encodeToString(ClientTarget.serializer(), target).encodeToByteArray())
                file.finishWrite(out)
            } catch (error: Exception) {
                file.failWrite(out)
                throw error
            }
        }
    }
}
