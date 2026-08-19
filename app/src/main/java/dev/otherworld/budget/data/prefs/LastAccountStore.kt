package dev.otherworld.budget.data.prefs

import android.content.Context

/**
 * Remembers which account the user posted to most recently, so Review can default to it
 * instead of always making the user pick. Holds no secret -- unlike [dev.otherworld.budget.data.auth.CredentialStore],
 * it does not belong in the encrypted store.
 */
interface LastAccountStore {
    fun get(): Long?
    fun set(id: Long)
    /**
     * Forgets the remembered account. Called on sign-out: the id refers to an account on the
     * server being disconnected from, so leaving it behind would have Review silently default
     * to a stale account id after signing in somewhere else.
     */
    fun clear()
}

/** Backed by plain [android.content.SharedPreferences]; never encrypted, see [LastAccountStore]'s KDoc. */
class SharedPreferencesLastAccountStore(context: Context) : LastAccountStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): Long? =
        prefs.getLong(KEY_ACCOUNT_ID, NO_VALUE).takeIf { it != NO_VALUE }

    override fun set(id: Long) {
        prefs.edit().putLong(KEY_ACCOUNT_ID, id).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "budget_last_account"
        const val KEY_ACCOUNT_ID = "account_id"
        const val NO_VALUE = -1L
    }
}
