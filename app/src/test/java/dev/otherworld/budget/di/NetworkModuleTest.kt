package dev.otherworld.budget.di

import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.auth.LoginFlowClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NetworkModuleTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `the login flow client is never rewritten and carries no auth header, even with stale credentials for another server`() = runTest {
        // Simulates a user already signed in to a different server ("server A"). This
        // store is never wired into the login-flow client below -- NetworkModule's
        // loginFlowHttpClient() takes no CredentialStore parameter at all, so
        // BaseUrlInterceptor/AuthInterceptor cannot be installed on it, by construction.
        val staleStore = InMemoryCredentialStore().apply {
            save(Credentials("https://server-a.example", "adam", "stale-password"))
        }
        assertEquals("https://server-a.example", staleStore.load()!!.server)

        val loginFlowClient = LoginFlowClient(NetworkModule.loginFlowHttpClient())

        server.enqueue(MockResponse().setBody(
            """{"poll":{"token":"tok","endpoint":"${server.url("/login/v2/poll")}"},
                "login":"${server.url("/login/flow")}"}"""
        ))

        loginFlowClient.start(server.url("/").toString().trimEnd('/')).getOrThrow()

        val request = server.takeRequest()
        assertEquals(server.hostName, request.requestUrl!!.host)
        assertEquals(server.port, request.requestUrl!!.port)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("OCS-APIRequest"))
    }
}
