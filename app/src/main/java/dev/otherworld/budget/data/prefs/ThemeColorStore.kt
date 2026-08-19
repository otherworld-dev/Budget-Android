package dev.otherworld.budget.data.prefs

import android.content.Context

/**
 * Remembers the Nextcloud theme colour last fetched for the signed-in server, as its hex string.
 * A colour is not a secret, so -- like [LastServerStore] and [LastAccountStore] -- it lives in
 * plain [android.content.SharedPreferences], never the encrypted credential store.
 *
 * The reason it is persisted at all is the cold-start flash: the theming colour is fetched over
 * the network after launch, so without a stored value every cold start would paint the default
 * Nextcloud blue first and then snap to the user's brand colour a round-trip later. Reading the
 * stored hex synchronously at startup lets the brand colour be applied at the very first
 * composition instead. [dev.otherworld.budget.data.theme.ThemeController] owns the read/write and
 * clears this on sign-out and on a server switch (see its KDoc and
 * [dev.otherworld.budget.data.auth.SessionManager]).
 */
interface ThemeColorStore {
    fun get(): String?
    fun set(hex: String)
    /** Forgets the stored colour, so the app falls back to the default until a new one is fetched. */
    fun clear()
}

/** Backed by plain [android.content.SharedPreferences]; never encrypted, see [ThemeColorStore]'s KDoc. */
class SharedPreferencesThemeColorStore(context: Context) : ThemeColorStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY_COLOR, null)

    override fun set(hex: String) {
        prefs.edit().putString(KEY_COLOR, hex).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "budget_theme_color"
        const val KEY_COLOR = "color"
    }
}
