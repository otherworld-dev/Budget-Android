package dev.otherworld.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun `application id is the published one`() {
        assertEquals("dev.otherworld.budget", BuildConfig.APPLICATION_ID)
    }
}
