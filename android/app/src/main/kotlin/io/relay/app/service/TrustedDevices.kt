package io.relay.app.service

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

@Serializable
data class TrustedDevice(val publicKey: String, val name: String, val enabled: Boolean = true)

/** Installation-local allowlist. Only public peer identities are persisted. */
class TrustedDevices private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "linqavo-trusted-devices.json"))
    private var roster = read()
    private val state = MutableStateFlow(roster.devices)
    val devices = state.asStateFlow()

    private fun read(): TrustedRoster = try {
        Json.decodeFromString<TrustedRoster>(file.readFully().decodeToString())
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        TrustedRoster()
    }

    @Synchronized fun retired(key: String) = key in roster.revoked
    @Synchronized fun remember(key: String, name: String) = save(roster.remember(key, name))
    @Synchronized fun forget(key: String) = save(roster.forget(key))
    @Synchronized fun revoke(key: String) = save(roster.revoke(key))
    @Synchronized fun setEnabled(key: String, enabled: Boolean) = save(roster.setEnabled(key, enabled))

    private fun save(next: TrustedRoster) {
        if (next == roster) return
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(TrustedRoster.serializer(), next).encodeToByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        if (read() != next) throw java.io.IOException("Trust record was not saved")
        roster = next
        state.value = next.devices
    }

    companion object {
        private var instance: TrustedDevices? = null
        @Synchronized fun get(context: Context): TrustedDevices =
            instance ?: TrustedDevices(context.applicationContext).also { instance = it }
    }
}
