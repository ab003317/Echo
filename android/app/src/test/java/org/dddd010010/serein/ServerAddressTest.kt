package org.dddd010010.serein

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    @Test fun acceptsDomainAndLocalDeployment() {
        assertEquals("https://music.example.com/music",ServerAddress.normalize(" music.example.com/ "))
        assertEquals("http://10.0.0.8:18082/music",ServerAddress.normalize("http://10.0.0.8:18082/music/"))
        assertEquals("http://[::1]:18082/music",ServerAddress.normalize("http://[::1]:18082"))
    }
    @Test fun rejectsCredentialsAndWrongPaths() {
        for(value in listOf("https://user:pass@example.com","https://example.com/api","file:///etc/passwd","https://example.com?key=secret")) {
            try { ServerAddress.normalize(value);throw AssertionError("Accepted invalid address") } catch(_:IllegalArgumentException) {}
        }
    }
}
