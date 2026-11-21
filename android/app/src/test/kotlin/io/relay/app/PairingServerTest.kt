package io.relay.app

import io.relay.app.core.WgParams
import io.relay.app.net.PairingServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
private fun StringReader(text: String) = ByteArrayInputStream(text.toByteArray())

class PairingServerTest {
    private val vectors = SharedContracts.json("enrollment-v2-vectors.json").jsonObject
    @Test fun onlyVersionedPublicKeyRequestsCanReachApproval() {
        assertEquals(PairingServer.Request.PAIR, PairingServer.classify(vectors.getValue("request").toString()))
        for (request in vectors.getValue("reject").jsonArray) assertNotEquals(PairingServer.Request.PAIR, PairingServer.classify(request.toString()))
        assertEquals(PairingServer.Request.WRONG_VERSION, PairingServer.classify("{\"v\":1,\"pair\":1}"))
    }
    @Test fun responseCannotExposeClientPrivateKey() {
        val key = vectors.getValue("request").jsonObject.getValue("clientPublicKey").jsonPrimitive.content
        val response = PairingServer.accepted(PairingServer.Configuration("192.168.1.14", 51821,
            WgParams(key,"never-send-this-private-key","0.0.0.0/0",51821,"1.1.1.1")))
        assertFalse(response.contains("private", ignoreCase=true))
        assertEquals(vectors.getValue("reply"), Json.parseToJsonElement(response))
    }
    @Test fun lineLengthAndTerminationAreBounded() {
        assertEquals(PairingServer.Request.IGNORE, PairingServer.classify("[".repeat(2000) + "]".repeat(2000)))
        assertTrue(io.relay.app.core.BoundedJson.shallow("{\"name\":\"" + "[".repeat(64) + "\"}"))
        val reader=StringReader("x".repeat(8192)+"\n")
        assertNull(PairingServer.readBoundedLine(reader,4096))
        assertEquals('x'.code, reader.read())
        assertNull(PairingServer.readBoundedLine(StringReader("bad\u0000\n"),4096))
        assertEquals("{}",PairingServer.readBoundedLine(StringReader("{}\n"),4096))
    }
}
