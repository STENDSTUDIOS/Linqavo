package io.relay.app

import io.relay.app.core.LocalNetworkPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test fun olderAndroidDoesNotRequireTheNewPermission() {
        for (sdk in 26..36) assertTrue(LocalNetworkPermission.canShare(sdk, false))
    }

    @Test fun android17AndLaterRequireTheCurrentGrant() {
        for (sdk in listOf(37, 38)) {
            assertFalse(LocalNetworkPermission.canShare(sdk, false))
            assertTrue(LocalNetworkPermission.canShare(sdk, true))
            assertFalse(LocalNetworkPermission.canShare(sdk, false)) // revoked
        }
    }
}
