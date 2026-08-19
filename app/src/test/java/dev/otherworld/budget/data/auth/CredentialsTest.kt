package dev.otherworld.budget.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    // A data class's generated toString() prints every property, so a Credentials caught in a
    // crash report, a log line, or an exception message would leak the Nextcloud app password.
    @Test fun `toString does not expose the app password`() {
        val creds = Credentials(
            server = "https://cloud.example",
            loginName = "alice",
            appPassword = "aabbcc-super-secret-token",
        )
        assertFalse(creds.toString().contains("aabbcc-super-secret-token"))
    }

    @Test fun `toString still identifies the server and login for debugging`() {
        val creds = Credentials("https://cloud.example", "alice", "secret")
        assertTrue(creds.toString().contains("https://cloud.example"))
        assertTrue(creds.toString().contains("alice"))
    }
}
