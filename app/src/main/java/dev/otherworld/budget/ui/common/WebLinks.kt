package dev.otherworld.budget.ui.common

/**
 * The one place a Budget web-app URL is built, so every screen that opens one uses the exact
 * same hash rather than each hand-rolling its own copy that could quietly drift apart.
 *
 * [transaction] is the only hash actually verified against the web app's router -- it's what
 * Recent/Activity has opened since before this file existed. [accounts], [budget] and [bills]
 * follow the same `#section` shape by inspection of the same router, for Overview's taps, but
 * are unverified: nothing has opened them for real yet. If one 404s in the web app, fix it here.
 */
object WebLinks {
    fun transaction(server: String, id: Long) = "$server/apps/budget/#transactions?id=$id"
    fun accounts(server: String) = "$server/apps/budget/#accounts"
    fun budget(server: String) = "$server/apps/budget/#budget"
    fun bills(server: String) = "$server/apps/budget/#bills"
}
