package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.remote.dto.ErrorDataDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException

object ErrorMapper {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * **401 only** becomes [BudgetApiError.Unauthorized]. That error now signs the user out --
     * [dev.otherworld.budget.data.auth.CredentialExpiry] clears the stored app password and the
     * UI returns to Onboarding -- so it must mean "this app password was rejected" and nothing
     * else.
     *
     * A 403 does not mean that. It is what a reverse proxy or WAF returns for a request it
     * dislikes, what an account without access to the Budget app gets, and (per
     * `docs/server-api-contract.md`) what Nextcloud's own `SecurityMiddleware` returns when a
     * route is implemented on a plain `Controller` instead of an `OCSController` -- a *server
     * deployment* problem, identical on every request. Folding it into `Unauthorized` signed the
     * user out for all three, and since re-logging in cannot change any of them, straight back
     * out again on the next request: a sign-out loop with no working credentials at the end of
     * it. It is reported as [BudgetApiError.ServerError] carrying 403, which leaves the session
     * intact and the queue retrying.
     */
    fun fromHttp(code: Int, body: String?): BudgetApiError {
        if (code == 401) return BudgetApiError.Unauthorized
        return when (errorCodeOf(body)) {
            "ocr_not_configured" -> BudgetApiError.OcrNotConfigured
            "ocr_quota_exhausted" -> BudgetApiError.OcrQuotaExhausted
            "ocr_extraction_failed" -> BudgetApiError.ExtractionFailed
            // The two 409s the server added once the idempotency key went live (409 alone does not
            // disambiguate them, so both are matched by error_code, not status):
            //   request_in_flight       -- a concurrent post of the same key is committing; retry.
            //   idempotency_key_conflict -- the key already maps to a different purchase; burned.
            // See BudgetApiError.RequestInFlight / IdempotencyKeyConflict and
            // docs/server-api-contract.md.
            "request_in_flight" -> BudgetApiError.RequestInFlight
            "idempotency_key_conflict" -> BudgetApiError.IdempotencyKeyConflict
            else -> BudgetApiError.ServerError(code)
        }
    }

    /**
     * Never maps a [CancellationException]: cancellation is not a server outcome, and turning it
     * into `ServerError(0)` made a torn-down request look like a definitive answer -- the exact
     * confusion that once let a cancelled save record a completed attempt against a queue row.
     * It is rethrown so structured concurrency keeps working through every mapping site.
     */
    fun fromThrowable(t: Throwable): BudgetApiError = when (t) {
        is CancellationException -> throw t
        is BudgetApiError -> t
        is IOException -> BudgetApiError.Network(t)
        else -> BudgetApiError.ServerError(0)
    }

    /** Never throws — a malformed error body must not mask the original failure. */
    private fun errorCodeOf(body: String?): String? = body?.let {
        runCatching {
            json.decodeFromString<OcsResponse<ErrorDataDto>>(it).ocs.data.errorCode
        }.getOrNull()
    }
}
