package dev.otherworld.budget.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperTest {

    private fun ocsError(code: Int, errorCode: String?) = """
        {"ocs":{"meta":{"status":"failure","statuscode":$code,"message":"…"},
        "data":${if (errorCode == null) "{}" else """{"error_code":"$errorCode"}"""}}}
    """.trimIndent()

    @Test
    fun `412 with ocr_not_configured maps to OcrNotConfigured`() {
        assertTrue(ErrorMapper.fromHttp(412, ocsError(412, "ocr_not_configured"))
            is BudgetApiError.OcrNotConfigured)
    }

    @Test
    fun `429 with ocr_quota_exhausted maps to OcrQuotaExhausted`() {
        assertTrue(ErrorMapper.fromHttp(429, ocsError(429, "ocr_quota_exhausted"))
            is BudgetApiError.OcrQuotaExhausted)
    }

    @Test
    fun `422 with ocr_extraction_failed maps to ExtractionFailed`() {
        assertTrue(ErrorMapper.fromHttp(422, ocsError(422, "ocr_extraction_failed"))
            is BudgetApiError.ExtractionFailed)
    }

    @Test
    fun `401 and only 401 maps to Unauthorized, regardless of body`() {
        assertTrue(ErrorMapper.fromHttp(401, null) is BudgetApiError.Unauthorized)
        assertTrue(ErrorMapper.fromHttp(401, ocsError(401, "something_new"))
            is BudgetApiError.Unauthorized)
    }

    @Test
    fun `403 is a server error, not Unauthorized`() {
        // Unauthorized clears the stored app password and drops the user back at Onboarding, so
        // it has to mean "this password was rejected". A 403 does not: a reverse proxy or WAF,
        // an account without access to the Budget app, and a route mounted on a plain Controller
        // instead of an OCSController all return one, and none of them is fixed by signing in
        // again -- so folding 403 in here signed the user out on a loop.
        val error = ErrorMapper.fromHttp(403, null)

        assertFalse(error is BudgetApiError.Unauthorized)
        assertEquals(403, (error as BudgetApiError.ServerError).code)
    }

    @Test
    fun `5xx maps to ServerError carrying the code`() {
        val error = ErrorMapper.fromHttp(503, null)
        assertEquals(503, (error as BudgetApiError.ServerError).code)
    }

    @Test
    fun `409 with request_in_flight maps to RequestInFlight`() {
        // A concurrent post of the same idempotency key is committing; the client retries and
        // joins the winner. Matched by error_code, not the bare 409 (which also carries the
        // conflict below).
        assertTrue(ErrorMapper.fromHttp(409, ocsError(409, "request_in_flight"))
            is BudgetApiError.RequestInFlight)
    }

    @Test
    fun `409 with idempotency_key_conflict maps to IdempotencyKeyConflict`() {
        assertTrue(ErrorMapper.fromHttp(409, ocsError(409, "idempotency_key_conflict"))
            is BudgetApiError.IdempotencyKeyConflict)
    }

    @Test
    fun `a bare 409 with no known error_code is just a ServerError`() {
        assertEquals(409, (ErrorMapper.fromHttp(409, null) as BudgetApiError.ServerError).code)
    }

    @Test
    fun `unknown error_code falls back to ServerError rather than crashing`() {
        assertTrue(ErrorMapper.fromHttp(412, ocsError(412, "something_new"))
            is BudgetApiError.ServerError)
    }

    @Test
    fun `malformed body does not throw`() {
        assertTrue(ErrorMapper.fromHttp(412, "not json at all") is BudgetApiError.ServerError)
    }

    @Test
    fun `IO failures map to Network`() {
        assertTrue(ErrorMapper.fromThrowable(java.io.IOException("offline"))
            is BudgetApiError.Network)
    }

    @Test
    fun `cancellation is rethrown, never mapped to an error`() {
        // CancellationException is not an IOException, so the old mapping turned it into
        // ServerError(0): a torn-down request dressed up as a definitive server answer, which is
        // how a cancelled save once recorded a completed attempt. Cancellation must propagate.
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ErrorMapper.fromThrowable(kotlinx.coroutines.CancellationException("torn down"))
        }
    }
}
