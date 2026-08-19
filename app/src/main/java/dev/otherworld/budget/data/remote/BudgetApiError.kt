package dev.otherworld.budget.data.remote

sealed class BudgetApiError(message: String) : Exception(message) {
    /** Server has no OCR backend configured. Neutral copy; no steering. */
    data object OcrNotConfigured : BudgetApiError("OCR not configured")
    /** Server's OCR quota is spent. Rendered identically to OcrNotConfigured. */
    data object OcrQuotaExhausted : BudgetApiError("OCR quota exhausted")
    /** Backend ran but could not read the receipt. Review opens with empty fields. */
    data object ExtractionFailed : BudgetApiError("Extraction failed")
    /** App password rejected. Clear credentials and re-onboard. */
    data object Unauthorized : BudgetApiError("Unauthorized")
    /** Offline, DNS failure, timeout. Queue and retry. */
    data class Network(val cause_: Throwable?) : BudgetApiError("Network failure")
    data class ServerError(val code: Int) : BudgetApiError("Server error $code")

    /**
     * `409 request_in_flight` -- another `POST transactions` carrying *this same idempotency key*
     * is still being processed by the server, so it declined to start a second writer. The winner
     * is committing right now; replaying the same request in a moment returns its id. Transient,
     * and safe to auto-retry precisely because the key guarantees the replay joins the same
     * transaction rather than creating a new one -- see [isRetryable].
     */
    data object RequestInFlight : BudgetApiError("Request already in flight")

    /**
     * `409 idempotency_key_conflict` -- the server already holds a *different* purchase under this
     * idempotency key (its stored request body does not match this one). With a fresh UUID minted
     * per queue row this should never occur, so it signals a burned key, not a transient state:
     * re-sending the same key can only hit the same conflict. The row is rotated onto a new key and
     * returned to mandatory review rather than auto-retried -- see
     * [dev.otherworld.budget.data.repo.ReceiptRepository.post].
     */
    data object IdempotencyKeyConflict : BudgetApiError("Idempotency key conflict")

    /**
     * True when a failed `POST transactions` may be re-sent unattended
     * ([dev.otherworld.budget.data.repo.ReceiptQueue.retryFailedPosts]); false when it must go back
     * to the user for review instead.
     *
     * Every post now carries a per-row **idempotency key** (a UUID stored on the queue row and
     * replayed on every attempt), and the server reserves that key before it writes, so a second
     * delivery of the same request can only ever *join* the transaction the first one created --
     * never duplicate it. That removes the question this decision used to turn on ("did the request
     * reach the server?"): with the key, a replay is duplicate-safe regardless. The former
     * `provesRequestNeverSent()` and its allowlist of "no connection was ever established" failures
     * are gone with it. Charging the user twice is still the worst bug this app can have, which is
     * why the rule lives here, in one place -- but the key, not this predicate, is what now
     * prevents it.
     *
     * So the axis is simply "will an unattended retry plausibly succeed, or does it need a human?":
     * - **Transient** -- any [Network] failure (offline, DNS, refused, read/connect timeout,
     *   connection reset) and any 5xx -- retries on their own. A read timeout or a proxy's 504 that
     *   may have committed the transaction is now in this set too, because the replay carries the
     *   key: this is the "silent recovery" the old design could not take.
     * - [RequestInFlight] -- the server is committing the winner under this key; replaying returns
     *   its id.
     * - Everything else needs a human and is **not** auto-retried: [Unauthorized] (re-auth first),
     *   [IdempotencyKeyConflict] (burned key, handled separately), a 4xx other than 409, and the
     *   OCR errors (which a POST never produces). A new failure mode added later defaults to the
     *   safe answer, `false`.
     */
    fun isRetryable(): Boolean = when (this) {
        is Network -> true
        is ServerError -> code in 500..599
        RequestInFlight -> true
        else -> false
    }
}
