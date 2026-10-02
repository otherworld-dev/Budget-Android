package dev.otherworld.budget.ui.common

/**
 * The one place a Budget web-app URL is built, so every screen that opens one uses the exact
 * same hash rather than each hand-rolling its own copy that could quietly drift apart.
 *
 * All four were opened from the app on a phone against a live server (2026-10-02). [accounts],
 * [budget] and [bills] land on those pages. [transaction] opens that transaction's edit form on
 * a server whose web app reads `?id=`, which was added alongside the check routes. An older web
 * app ignores the id and just shows the Transactions list, which is still a sensible place to
 * land.
 */
object WebLinks {
    fun transaction(server: String, id: Long) = "$server/apps/budget/#transactions?id=$id"
    fun accounts(server: String) = "$server/apps/budget/#accounts"
    fun budget(server: String) = "$server/apps/budget/#budget"
    fun bills(server: String) = "$server/apps/budget/#bills"
}
