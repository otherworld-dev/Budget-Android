package dev.otherworld.budget.data.prefs

import android.content.Context

/**
 * Remembers which server the app last completed a sign-in against. A URL is not a secret, so
 * this lives in plain [android.content.SharedPreferences] (same reasoning as
 * [LastAccountStore]) -- and, crucially, it survives
 * [dev.otherworld.budget.data.auth.CredentialExpiry], which clears the encrypted credential
 * store but not this. That survival is the point: after an expiry the queue's rows outlive the
 * credentials by design, so when the user next signs in this is the only record of which
 * server those rows' account/category ids belong to.
 * [dev.otherworld.budget.data.auth.SessionManager.signIn] compares it and quarantines FAILED
 * rows when the server changed, instead of letting them auto-post foreign ids to the new one.
 */
interface LastServerStore {
    fun get(): String?
    fun set(server: String)
}

/** Backed by plain SharedPreferences; never encrypted, see [LastServerStore]'s KDoc. */
class SharedPreferencesLastServerStore(context: Context) : LastServerStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY_SERVER, null)

    override fun set(server: String) {
        prefs.edit().putString(KEY_SERVER, server).apply()
    }

    private companion object {
        const val PREFS_NAME = "budget_last_server"
        const val KEY_SERVER = "server"
    }
}
