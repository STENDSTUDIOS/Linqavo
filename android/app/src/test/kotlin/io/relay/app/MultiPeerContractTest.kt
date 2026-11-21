package io.relay.app

import io.relay.app.core.PeerSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultiPeerContractTest {
    @Test fun capacityAndLeasesHaveSharedAuthority() {
        val contract = SharedContracts.json("multi-peer.json").jsonObject
        assertEquals(3, contract.getValue("maximumCapacity").jsonPrimitive.int)
        assertEquals(3, contract.getValue("defaultCapacity").jsonPrimitive.int)
        val leases = contract.getValue("leases").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("10.13.37.2/32", "10.13.37.3/32", "10.13.37.4/32"), leases)
    }
    @Test fun publicPeerStateCarriesIdentityAndIndependentCounters() {
        val peers = listOf(PeerSnapshot("public", "PC", "10.13.37.3/32", true, "connected", 1, 2, 3, 100, 200))
        val text = Json.encodeToString(peers)
        assertFalse(text.contains("private", ignoreCase = true))
        assertEquals(peers, Json.decodeFromString<List<PeerSnapshot>>(text))
    }
}
