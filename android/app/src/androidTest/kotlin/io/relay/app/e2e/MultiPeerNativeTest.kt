package io.relay.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wireguard.crypto.KeyPair
import io.relay.app.core.WgConfig
import io.relay.app.net.wg.GoWgForwarder
import io.relay.app.net.wg.WgKeys
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native bridge/registry test. Actual concurrent tunnel traffic is tested in Go. */
@RunWith(AndroidJUnit4::class)
class MultiPeerNativeTest {
    @get:Rule val permission = LocalNetworkGrantRule()
    @Test fun livePeerOperationsPreserveOtherNativeRegistryEntries() {
        val forwarder = GoWgForwarder()
        try {
            forwarder.start(WgConfig.serverConfig(WgKeys.generate()))
            assertTrue(forwarder.setCapacity(3))
            val keys = (0..2).map { KeyPair().publicKey.toBase64() }
            for ((index,key) in keys.withIndex())
                assertEquals("10.13.37.${index+2}/32", forwarder.enrollPeer(key,"PC-$index"))
            assertEquals(3, forwarder.peers().size)
            assertTrue(forwarder.peers().all { it.lastHandshake == 0L && it.status == "authorized" })
            assertTrue(forwarder.setPeerActive(keys[1],false))
            assertEquals("disabled", forwarder.peers().first { it.publicKey == keys[1] }.status)
            assertTrue(forwarder.removePeer(keys[1]))
            assertEquals(setOf(keys[0],keys[2]), forwarder.peers().map { it.publicKey }.toSet())
            assertFalse(forwarder.setCapacity(1))
        } finally { forwarder.stop() }
        assertTrue(forwarder.peers().isEmpty())
    }
}
