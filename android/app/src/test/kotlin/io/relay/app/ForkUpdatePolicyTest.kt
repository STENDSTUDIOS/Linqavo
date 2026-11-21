package io.relay.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import io.relay.app.core.GitHubRelease

class ForkUpdatePolicyTest {
    private val project = "https://github.com/STENDSTUDIOS/Linqavo/releases"
    private val name = "Linqavo-1.10.0-android-arm64-debug.apk"
    private val url = "$project/download/v1.10.0/$name"
    private val json = """{"tag_name":"v1.10.0","html_url":"$project/tag/v1.10.0","draft":false,"prerelease":false,
        "assets":[{"name":"$name","state":"uploaded","browser_download_url":"$url"}]}"""

    @Test fun selectsOfficialApkOnlyForANewerCompatibleRelease() {
        assertEquals(url, GitHubRelease.find(json, "1.9.0", true)?.download)
        assertNull(GitHubRelease.find(json, "1.10.0", true))
        assertNull(GitHubRelease.find(json, "2.0.0", true))
        assertTrue(runCatching { GitHubRelease.find(json, "1.0.0", false) }.isFailure)
        for (invalid in listOf(json.replace(url, "https://example.org/app.apk"), json.replace("false", "true"),
            json.replace("uploaded", "new"), json.replace("STENDSTUDIOS", "another-owner"))) {
            assertTrue(runCatching { GitHubRelease.find(invalid, "1.0.0", true) }.isFailure)
        }
    }

    @Test fun noBackgroundServiceOrInstallerHooks() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "shared/update-policy.json").exists() }
        fun source(path: String) = File(root, path).readText()
        val base = "android/app/src/main/kotlin/io/relay/app/"
        assertFalse(source(base + "client/ClientVpnService.kt").contains("UpdateFetcher."))
        assertFalse(source(base + "service/SharingService.kt").contains("UpdateFetcher."))
        val fetcher = source(base + "service/UpdateFetcher.kt")
        for (denied in listOf("Mahdi-mortazavi", "startActivity", "FileProvider")) {
            assertFalse(denied, fetcher.contains(denied))
        }
        assertFalse(source("android/app/src/main/AndroidManifest.xml").contains("REQUEST_INSTALL_PACKAGES"))
    }
}
