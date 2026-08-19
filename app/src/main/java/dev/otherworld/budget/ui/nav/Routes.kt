package dev.otherworld.budget.ui.nav

object Routes {
    const val WELCOME = "welcome"
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    const val QUICK_ADD = "quickAdd"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val REVIEW_PATTERN = "review/{receiptId}"
    fun review(id: Long) = "review/$id"
}
