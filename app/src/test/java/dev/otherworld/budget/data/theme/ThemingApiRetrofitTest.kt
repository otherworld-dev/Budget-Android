package dev.otherworld.budget.data.theme

import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.TestApiFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the theming fetch against a real [MockWebServer] over the same authenticated Retrofit
 * stack the Budget calls use (via [TestApiFactory]), the way [dev.otherworld.budget.data.remote.BudgetApiRetrofitTest]
 * does. The colour route is a Nextcloud *core* OCS route -- it works against a real server today,
 * unlike the Budget routes -- so this is genuinely representative.
 */
class ThemingApiRetrofitTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ThemingApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val store = InMemoryCredentialStore().apply {
            save(Credentials(server.url("/").toString().trimEnd('/'), "adam", "pw"))
        }
        api = ThemingApiRetrofit(TestApiFactory.service(store))
    }

    @After fun tearDown() = server.shutdown()

    private fun ocs(data: String) = MockResponse().setBody(
        """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""
    )

    @Test
    fun `parses theming color from a realistic core capabilities envelope`() = runTest {
        // A trimmed but shape-accurate slice of a real /ocs/v2.php/cloud/capabilities body: many
        // sibling keys the app never models, which ignoreUnknownKeys must drop.
        server.enqueue(ocs("""
            {
              "version": {"major": 30, "minor": 0, "string": "30.0.1"},
              "capabilities": {
                "core": {"pollinterval": 60},
                "theming": {
                  "name": "Nextcloud",
                  "url": "https://nextcloud.com",
                  "color": "#0082c9",
                  "color-text": "#ffffff",
                  "color-element": "#0082c9"
                }
              }
            }
        """.trimIndent()))

        assertEquals("#0082c9", api.themeColor())
    }

    @Test
    fun `hits the core OCS path with the three OCS headers`() = runTest {
        server.enqueue(ocs("""{"capabilities":{"theming":{"color":"#abcdef"}}}"""))

        api.themeColor()

        val request = server.takeRequest()
        assertEquals("/ocs/v2.php/cloud/capabilities", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `missing theming block yields null so the caller keeps the default`() = runTest {
        server.enqueue(ocs("""{"version":{"major":25},"capabilities":{"core":{}}}"""))
        assertNull(api.themeColor())
    }

    @Test
    fun `missing color field yields null`() = runTest {
        server.enqueue(ocs("""{"capabilities":{"theming":{"name":"Nextcloud"}}}"""))
        assertNull(api.themeColor())
    }

    @Test
    fun `a non-2xx response yields null rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(api.themeColor())
    }

    @Test
    fun `an unparseable body yields null rather than propagating`() = runTest {
        server.enqueue(MockResponse().setBody("not json at all"))
        assertNull(api.themeColor())
    }
}
