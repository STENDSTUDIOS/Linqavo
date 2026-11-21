package io.relay.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import io.relay.app.service.UpdateFetcher

class ForkUpdatePolicyTest {
    @Test fun manualCheckIsUnconfigured() = runTest {
        assertNull(UpdateFetcher.latestVersion())
    }
    @Test fun lifecycleHasNoUpdateHooksAndFetcherHasNoTransport() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "shared/update-policy.json").exists() }
        fun source(path: String) = File(root, path).readText()
        val base = "android/app/src/main/kotlin/io/relay/app/"
        assertFalse(source(base + "MainActivity.kt").contains("viewModel.checkForUpdate("))
        assertFalse(source(base + "service/SharingService.kt").contains("UpdateFetcher."))
        val fetcher = source(base + "service/UpdateFetcher.kt")
        for (denied in listOf("java.net", "HttpURLConnection", "Mahdi-mortazavi", "startActivity", "FileProvider")) {
            assertFalse(denied, fetcher.contains(denied))
        }
        assertFalse(source("android/app/src/main/AndroidManifest.xml").contains("REQUEST_INSTALL_PACKAGES"))
    }
}
