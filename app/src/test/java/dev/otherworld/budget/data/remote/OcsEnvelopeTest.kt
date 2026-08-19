package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.remote.dto.AccountDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OcsEnvelopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unwraps the ocs envelope`() {
        val body = """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},
            "data":[{"id":1,"name":"Current Account","currency":"GBP"}]}}
        """.trimIndent()
        val parsed = json.decodeFromString<OcsResponse<List<AccountDto>>>(body)
        assertEquals("Current Account", parsed.ocs.data.single().name)
    }

    @Test
    fun `tolerates unknown fields the server may add later`() {
        val body = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
            "data":[{"id":1,"name":"X","currency":"GBP","colour":"#fff"}]}}
        """.trimIndent()
        assertEquals(1L, json.decodeFromString<OcsResponse<List<AccountDto>>>(body).ocs.data.single().id)
    }
}
