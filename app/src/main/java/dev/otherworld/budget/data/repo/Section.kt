package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.remote.BudgetApiError
import java.time.Instant

/**
 * One check-screen section's state. [data] and [error] are independent on purpose: a failed
 * refresh keeps the last data (and its [fetchedAt]) and sets [error] alongside it, so the screen
 * can show "Updated 4 min ago" over real figures instead of blanking them.
 */
data class Section<T>(
    val data: T? = null,
    val fetchedAt: Instant? = null,
    val refreshing: Boolean = false,
    val error: BudgetApiError? = null,
    /** The server can't serve this section (no check_available, or the route 404s/501s). */
    val unsupported: Boolean = false,
)
