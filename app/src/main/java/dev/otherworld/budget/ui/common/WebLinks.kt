package dev.otherworld.budget.ui.common

/**
 * The one place a Budget web-app URL is built, so every screen that opens one uses the exact
 * same hash rather than each hand-rolling its own copy that could quietly drift apart.
 *
 * All four were opened from the app on a phone against a live server (2026-10-02). [accounts],
 * [budget] and [bills] land on those pages. [transaction] lands on the Transactions page, but the
 * web app currently ignores `?id=`, so it shows the list rather than that one transaction. The id
 * is kept so the link starts working as soon as the web app reads it.
 */
object WebLinks {
    fun transaction(server: String, id: Long) = "$server/apps/budget/#transactions?id=$id"
    fun accounts(server: String) = "$server/apps/budget/#accounts"
    fun budget(server: String) = "$server/apps/budget/#budget"
    fun bills(server: String) = "$server/apps/budget/#bills"
}
