package dev.otherworld.budget.data.theme

import dev.otherworld.budget.data.remote.BudgetService
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Fetches the signed-in server's theming colour from Nextcloud core's
 * `GET /ocs/v2.php/cloud/capabilities`. Returns the raw hex string (e.g. `"#0082c9"`) or `null`
 * for *any* reason it cannot be obtained -- the call failing, a non-2xx status, an older server
 * with no theming block, or a missing `color` field.
 *
 * Theming is cosmetic: a failure here must never break anything and never signs the user out, so
 * -- unlike the Budget endpoints -- it does **not** go through [dev.otherworld.budget.data.remote.BudgetApiRetrofit]'s
 * `call()` (and therefore never raises [dev.otherworld.budget.data.auth.AuthExpiry]). It simply
 * swallows every failure into `null`. It is deliberately independent of the Budget routes so that
 * their 404 (today's reality against a server without the Budget app) does not suppress the theme
 * fetch, which hits a core route that answers 200.
 */
interface ThemingApi {
    suspend fun themeColor(): String?
}

class ThemingApiRetrofit @Inject constructor(
    private val service: BudgetService,
) : ThemingApi {

    override suspend fun themeColor(): String? {
        return try {
            val response = service.coreCapabilities()
            if (!response.isSuccessful) return null
            response.body()?.ocs?.data?.capabilities?.theming?.color
        } catch (e: CancellationException) {
            // Structured concurrency: a torn-down fetch is cancellation, not a "no colour" answer.
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
