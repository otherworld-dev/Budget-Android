package dev.otherworld.budget.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one place that decides whether a failed `POST transactions` may be re-sent unattended.
 * Since every post now carries a per-row idempotency key, the axis is "is the failure transient?"
 * -- not the old "did the request reach the server?", which the key made moot.
 */
class BudgetApiErrorTest {

    @Test fun `every network failure is retryable now the key protects the replay`() {
        // Refused and DNS were retryable before (nothing was sent); a read timeout and a
        // mid-response reset were NOT (the server might have committed). The key flips the latter
        // two: replaying the same key can only join the transaction, never duplicate it.
        assertTrue(BudgetApiError.Network(ConnectException("refused")).isRetryable())
        assertTrue(BudgetApiError.Network(UnknownHostException("dns")).isRetryable())
        assertTrue(BudgetApiError.Network(SocketTimeoutException("timeout")).isRetryable())
        assertTrue(BudgetApiError.Network(SocketException("reset")).isRetryable())
        assertTrue(BudgetApiError.Network(null).isRetryable())
    }

    @Test fun `5xx is retryable, other server codes are not`() {
        assertTrue(BudgetApiError.ServerError(500).isRetryable())
        assertTrue(BudgetApiError.ServerError(503).isRetryable())
        assertTrue(BudgetApiError.ServerError(504).isRetryable())
        // 4xx (client/deployment) and the code-0 unknown are permanent from here -- a retry won't
        // change the answer, so the row goes to the user, not the sweep.
        assertFalse(BudgetApiError.ServerError(400).isRetryable())
        assertFalse(BudgetApiError.ServerError(403).isRetryable())
        assertFalse(BudgetApiError.ServerError(409).isRetryable())
        assertFalse(BudgetApiError.ServerError(0).isRetryable())
    }

    @Test fun `request_in_flight is retryable, key conflict is not`() {
        // request_in_flight: the winner is committing under this key -- replaying returns its id.
        assertTrue(BudgetApiError.RequestInFlight.isRetryable())
        // idempotency_key_conflict: the key is burned; re-sending it just hits the same wall.
        assertFalse(BudgetApiError.IdempotencyKeyConflict.isRetryable())
    }

    @Test fun `auth and OCR failures need a human, so they are not auto-retried`() {
        assertFalse(BudgetApiError.Unauthorized.isRetryable())
        assertFalse(BudgetApiError.OcrNotConfigured.isRetryable())
        assertFalse(BudgetApiError.OcrQuotaExhausted.isRetryable())
        assertFalse(BudgetApiError.ExtractionFailed.isRetryable())
    }
}
