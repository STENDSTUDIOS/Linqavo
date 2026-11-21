package io.relay.app.service

import io.relay.app.core.GitHubRelease
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object UpdateFetcher {
    suspend fun latest(installed: String, arm64: Boolean): GitHubRelease.Offer? = withContext(Dispatchers.IO) {
        val connection = URL(GitHubRelease.endpoint).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Linqavo")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val started = System.nanoTime()
            check(connection.responseCode == 200)
            val body = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(System.nanoTime() - started < 15_000_000_000L)
                    val count = stream.read(buffer)
                    if (count == -1) break
                    check(body.size() + count <= 256 * 1024)
                    body.write(buffer, 0, count)
                }
            }
            GitHubRelease.find(body.toString("UTF-8"), installed, arm64)
        } finally {
            connection.disconnect()
        }
    }
}
