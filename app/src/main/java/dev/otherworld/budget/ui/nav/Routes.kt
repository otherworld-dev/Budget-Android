package dev.otherworld.budget.ui.nav

object Routes {
    const val WELCOME = "welcome"
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    const val QUICK_ADD = "quickAdd"
    const val OVERVIEW = "overview"
    const val ACTIVITY = "activity"
    const val BUDGET = "budget"
    const val SETTINGS = "settings"
    const val REVIEW_PATTERN = "review/{receiptId}"
    fun review(id: Long) = "review/$id"
}

/**
 * The bottom bar's three tabs (Task 7), in the order [dev.otherworld.budget.ui.nav.BudgetBottomBar]
 * shows them. [BudgetNavHost] uses this to decide whether the current back-stack entry gets a
 * bottom bar at all -- [Routes.QUICK_ADD], [Routes.SETTINGS], [Routes.BUDGET] and the rest are
 * reached by pushing on top of a tab (or, for Welcome/Onboarding/Review, exist outside the tabs
 * entirely), so none of them belong here.
 */
val TOP_LEVEL_ROUTES = listOf(Routes.CAPTURE, Routes.OVERVIEW, Routes.ACTIVITY)
