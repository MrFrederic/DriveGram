package app.drivegram

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysTest {
    private val hash = "0123456789abcdef0123456789ABCDEF"

    @Test fun wholePage() =
        assertEquals("1234567" to hash.lowercase(), parseApiKeys("App configuration\nApp api_id:\n1234567\nApp api_hash:\n$hash\nTest configuration: 149.154.167.40:443"))

    @Test fun hashOnly() = assertEquals(null to hash.lowercase(), parseApiKeys(" $hash "))

    @Test fun idOnly() = assertEquals("1234567" to null, parseApiKeys("1234567"))

    @Test fun nothing() = assertEquals(null to null, parseApiKeys("hello"))
}
