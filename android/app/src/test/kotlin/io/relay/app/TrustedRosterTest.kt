package io.relay.app

import io.relay.app.service.TrustedRoster
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TrustedRosterTest {
    @Test fun savedKeysKeepTheirOwnAccessAcrossReload() {
        val roster = TrustedRoster().remember("first", "Laptop").remember("second", "Laptop")
            .setEnabled("second", false)
        val loaded = Json.decodeFromString<TrustedRoster>(Json.encodeToString(TrustedRoster.serializer(), roster))
        assertEquals(roster, loaded)
        assertFalse(loaded.devices.single { it.publicKey == "second" }.enabled)
        assertEquals(roster, loaded.remember("second", "Changed name"))
        assertEquals(1, loaded.forget("first").devices.size)
        assertEquals(3, loaded.remember("third", "").remember("fourth", "").devices.size)
    }

    @Test fun revokedKeyCannotBeRememberedAgain() {
        var roster = TrustedRoster().remember("old", "Laptop").revoke("old")
        repeat(130) { roster = roster.revoke("another-$it") }
        assertTrue(roster.remember("old", "Laptop").devices.isEmpty())
        assertEquals("new", roster.remember("new", "Laptop").devices.single().publicKey)
    }
}
