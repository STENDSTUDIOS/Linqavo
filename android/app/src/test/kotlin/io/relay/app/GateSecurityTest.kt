package io.relay.app

import io.relay.app.net.ClientGate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GateSecurityTest {
    @Test fun staleAnswersAndExcessQueuesCannotAuthorizeClients() = runTest {
        val gate = ClientGate()
        val waiting = (0..7).map { async { gate.authorize("key-$it") } }
        runCurrent()
        assertFalse(gate.authorize("excess"))
        gate.resolve("not-the-visible-key", true)
        assertFalse(gate.isKnown("not-the-visible-key"))
        gate.reset()
        for (job in waiting) assertFalse(job.await())
        assertNull(gate.pending.value)
    }
}
