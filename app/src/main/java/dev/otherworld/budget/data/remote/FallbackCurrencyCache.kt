package dev.otherworld.budget.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Currency used when a payload omits its own, resolved from the server's capabilities the
 * first time it's needed (see [BudgetApiRetrofit.extract]). Null -- never a hardcoded
 * literal -- until then: the capabilities response is the sole source of truth for currency,
 * and guessing risks mis-labelling (and, via createTransaction, mis-saving) an amount.
 *
 * Its own tiny singleton rather than a field on [BudgetApiRetrofit] so the session-teardown
 * paths can reach it: this is *per-server* state, and letting it survive the session meant a
 * sign-out from a GBP server followed by a sign-in to a EUR one kept labelling drafts GBP for
 * the life of the process. Cleared by CredentialExpiry.onUnauthorized, SessionManager.signOut,
 * and SessionManager.signIn when the server changed.
 */
@Singleton
class FallbackCurrencyCache @Inject constructor() {
    @Volatile var value: String? = null

    fun clear() {
        value = null
    }
}
