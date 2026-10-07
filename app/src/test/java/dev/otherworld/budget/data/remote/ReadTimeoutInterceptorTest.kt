package dev.otherworld.budget.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReadTimeoutInterceptorTest {
    private val server = MockWebServer()
    private var seenReadTimeoutMs = -1

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    /** The production stack: a long default for receipt extraction, with the interceptor in front. */
    private fun client() = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(ReadTimeoutInterceptor())
        .addInterceptor(Interceptor { chain -> seenReadTimeoutMs = chain.readTimeoutMillis(); chain.proceed(chain.request()) })
        .build()

    @Test fun `a read gives up after the short timeout`() {
        server.enqueue(MockResponse().setBody("{}"))
        client().newCall(Request.Builder().url(server.url("/accounts")).build()).execute().close()
        assertEquals(TimeUnit.SECONDS.toMillis(ReadTimeoutInterceptor.READ_SECONDS), seenReadTimeoutMs.toLong())
    }

    @Test fun `a post keeps the long timeout receipt extraction needs`() {
        server.enqueue(MockResponse().setBody("{}"))
        val body = "x".toRequestBody("text/plain".toMediaType())
        client().newCall(Request.Builder().url(server.url("/ocr/extract")).post(body).build()).execute().close()
        assertEquals(TimeUnit.SECONDS.toMillis(120), seenReadTimeoutMs.toLong())
    }
}
