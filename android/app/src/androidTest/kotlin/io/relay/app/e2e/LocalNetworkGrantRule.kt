package io.relay.app.e2e

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.core.LocalNetworkPermission
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Existing networking journeys must run with the real Android 17 grant. */
class LocalNetworkGrantRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT >= LocalNetworkPermission.MIN_API) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName, LocalNetworkPermission.NAME,
                )
            }
            base.evaluate()
        }
    }
}
