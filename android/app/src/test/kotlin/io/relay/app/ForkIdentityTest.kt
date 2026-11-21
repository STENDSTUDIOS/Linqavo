package io.relay.app

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import javax.xml.parsers.DocumentBuilderFactory

class ForkIdentityTest {
    @Test fun versionAndIdentityCannotFallBackToUpstream() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File(SharedContracts.dir, "LinqavoVersion.props"))
        fun value(name: String) = xml.getElementsByTagName(name).item(0).textContent.trim()
        val version = value("LinqavoVersion")
        val parts = version.split(".").map(String::toInt)
        assertEquals(3, parts.size)
        val code = value("LinqavoVersionCode").toInt()
        assertTrue(code > 21101001)
        assertEquals(21101002 + (parts[0] - 1) * 10000 + parts[1] * 100 + parts[2], code)
        assertEquals("$version.0", value("LinqavoAssemblyVersion"))
        val identity = SharedContracts.json("linqavo-identity.json").jsonObject
        assertEquals("Linqavo", identity.getValue("name").jsonPrimitive.content)
        assertEquals("io.relay.fork", identity.getValue("androidApplicationId").jsonPrimitive.content)
        val app = java.io.File(SharedContracts.dir.parentFile, "android/app/build.gradle.kts").readText()
        assertTrue(app.contains("applicationId = \"io.relay.fork\""))
        assertTrue(app.contains("targetSdk = 37"))
        assertFalse(app.contains("?: \"0.1.0\""))
    }
}
