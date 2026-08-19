package dev.otherworld.budget.data.prefs

import android.content.Context

/**
 * Records whether the one-time welcome screen has been shown, so it appears on first run and never
 * again. A plain SharedPreferences flag -- it is not a secret, and it deliberately survives a
 * credential expiry / sign-out (like [LastServerStore]): the welcome is a first-*run* concept, not
 * a first-*sign-in* one, so signing out and back in must not replay it.
 */
interface WelcomeStore {
    fun seen(): Boolean
    fun markSeen()
}

/** Backed by plain SharedPreferences; never encrypted, see [WelcomeStore]'s KDoc. */
class SharedPreferencesWelcomeStore(context: Context) : WelcomeStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun seen(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    override fun markSeen() {
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "budget_welcome"
        const val KEY_SEEN = "seen"
    }
}
