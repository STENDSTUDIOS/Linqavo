package io.relay.app.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relay.app.service.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises restored preferences without starting a service or changing device routes. */
@RunWith(AndroidJUnit4::class)
class DirectForwardingSettingsTest {
    @Test fun staleProxyPreferencesAreRemovedWithoutLosingOtherSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A separate preference namespace avoids changing the installed app's settings.
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("direct-forwarding-test-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("relay", Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().clear()
                .putBoolean("require_vpn_proxy", true)
                .putBoolean("automatic_route", true)
                .putString("upstream_proxy", "127.0.0.1:1080")
                .putInt("maximum_clients", 2)
                .putString("theme_mode", "dark")
                .putBoolean("onboarded", true).commit())
            repeat(2) {
                val settings = Settings(isolated)
                for (key in listOf("require_vpn_proxy", "automatic_route", "upstream_proxy")) {
                    assertFalse("Restored setting survived: $key", prefs.contains(key))
                }
                assertEquals(2, settings.maximumClients)
                assertEquals("dark", settings.themeMode)
                assertTrue(settings.onboarded)
            }
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }
}
