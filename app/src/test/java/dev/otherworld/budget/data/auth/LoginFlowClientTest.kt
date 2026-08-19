package dev.otherworld.budget.data.auth

import dev.otherworld.budget.data.remote.BudgetApiError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class LoginFlowClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LoginFlowClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = LoginFlowClient(OkHttpClient(), pollIntervalMs = 1)
    }

    // One test shuts the server down mid-test to force IOExceptions, so shutdown
    // here may be a harmless no-op on an already-closed server.
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test
    fun `start posts to login v2 and parses the response`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"poll":{"token":"tok123","endpoint":"${server.url("/login/v2/poll")}"},
             "login":"${server.url("/login/flow")}"}
        """.trimIndent()))

        val start = client.start(server.url("/").toString().trimEnd('/')).getOrThrow()

        assertEquals("tok123", start.pollToken)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `poll keeps trying while the server returns 404 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""
            {"server":"https://cloud.example.com","loginName":"adam","appPassword":"app-pw"}
        """.trimIndent()))

        val credentials = client.poll(
            LoginFlowStart("ignored", "tok123", server.url("/login/v2/poll").toString())
        ).getOrThrow()

        assertEquals("adam", credentials.loginName)
        assertEquals("app-pw", credentials.appPassword)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `poll gives up after the timeout rather than looping forever`() = runTest {
        repeat(50) { server.enqueue(MockResponse().setResponseCode(404)) }
        val result = client.poll(
            LoginFlowStart("ignored", "tok123", server.url("/login/v2/poll").toString()),
            timeout = 20.milliseconds,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `poll fails immediately when a 200 body does not parse into credentials`() = runTest {
        server.enqueue(MockResponse().setBody("not valid json"))
        // If a parse failure were (wrongly) treated as "keep polling", these would let
        // it retry for the whole timeout; only asserting requestCount == 1 catches that.
        repeat(20) { server.enqueue(MockResponse().setResponseCode(404)) }

        val result = client.poll(
            LoginFlowStart("ignored", "tok123", server.url("/login/v2/poll").toString()),
            timeout = 50.milliseconds,
        )

        assertTrue(result.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `poll reports the last IOException as the cause when the deadline expires`() = runTest {
        val pollEndpoint = server.url("/login/v2/poll").toString()
        server.shutdown() // every connection attempt now raises an IOException

        val result = client.poll(
            LoginFlowStart("ignored", "tok123", pollEndpoint),
            timeout = 20.milliseconds,
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is BudgetApiError.Network)
        assertTrue((error as BudgetApiError.Network).cause_ is IOException)
    }
}
