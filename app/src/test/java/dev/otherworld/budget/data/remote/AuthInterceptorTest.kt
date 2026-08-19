package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInterceptorTest {

    @Test
    fun `adds basic auth and the OCS header when credentials exist`() {
        val server = MockWebServer().apply { enqueue(MockResponse()); start() }
        val store = InMemoryCredentialStore().apply {
            save(Credentials(server.url("/").toString().trimEnd('/'), "adam", "app-pw"))
        }
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()
            .newCall(Request.Builder().url(server.url("/x")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("Basic YWRhbTphcHAtcHc=", recorded.getHeader("Authorization"))
        assertEquals("true", recorded.getHeader("OCS-APIRequest"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        server.shutdown()
    }

    @Test
    fun `passes the request through untouched when there are no credentials`() {
        val server = MockWebServer().apply { enqueue(MockResponse()); start() }
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(InMemoryCredentialStore())).build()
            .newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertNull(server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }
}
