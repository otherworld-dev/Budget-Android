package dev.otherworld.budget.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlNormaliserTest {

    @Test
    fun `adds https when no scheme is given`() {
        assertEquals("https://cloud.example.com",
            ServerUrlNormaliser.normalise("cloud.example.com").getOrThrow())
    }

    @Test
    fun `strips trailing slashes`() {
        assertEquals("https://cloud.example.com",
            ServerUrlNormaliser.normalise("https://cloud.example.com///").getOrThrow())
    }

    @Test
    fun `preserves a subpath install`() {
        assertEquals("https://example.com/nextcloud",
            ServerUrlNormaliser.normalise("example.com/nextcloud/").getOrThrow())
    }

    @Test
    fun `rejects plaintext http on a public host`() {
        assertTrue(ServerUrlNormaliser.normalise("http://cloud.example.com").isFailure)
    }

    @Test
    fun `allows plaintext http on a private range so LAN instances work`() {
        assertEquals("http://192.168.1.10:8080",
            ServerUrlNormaliser.normalise("http://192.168.1.10:8080").getOrThrow())
        assertEquals("http://nextcloud.local",
            ServerUrlNormaliser.normalise("http://nextcloud.local").getOrThrow())
    }

    @Test
    fun `rejects blank and malformed input`() {
        assertTrue(ServerUrlNormaliser.normalise("   ").isFailure)
        assertTrue(ServerUrlNormaliser.normalise("ht!tp://x").isFailure)
    }

    @Test
    fun `accepts an IPv6 loopback literal over http and preserves brackets`() {
        assertEquals("http://[::1]:8080",
            ServerUrlNormaliser.normalise("http://[::1]:8080").getOrThrow())
    }

    @Test
    fun `accepts an IPv6 unique-local literal over http`() {
        assertEquals("http://[fd00::1]",
            ServerUrlNormaliser.normalise("http://[fd00::1]").getOrThrow())
    }

    @Test
    fun `rejects a public IPv6 literal over plaintext http`() {
        assertTrue(ServerUrlNormaliser.normalise("http://[2001:db8::1]").isFailure)
    }

    @Test
    fun `allows a public IPv6 literal over https`() {
        assertEquals("https://[2001:db8::1]",
            ServerUrlNormaliser.normalise("https://[2001:db8::1]").getOrThrow())
    }
}
