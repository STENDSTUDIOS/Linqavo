package io.relay.app.service

import android.content.Context

/** No trusted fork channel has been provisioned. No HTTP or installer entry points exist. */
object UpdateFetcher {
    sealed interface Result {
        data object Installing : Result
        data object Unavailable : Result
        data object ChecksumMismatch : Result
        data object Unverifiable : Result
    }
    suspend fun latestVersion(): String? = null
    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadAndInstall(context: Context): Result = Result.Unverifiable
}
