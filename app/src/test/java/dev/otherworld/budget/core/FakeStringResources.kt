package dev.otherworld.budget.core

import dev.otherworld.budget.R

/**
 * Test double for [StringResources] that returns the real English copy, so the plain-JUnit
 * ViewModel tests (no Context, no Robolectric) can keep asserting on user-facing text. Unknown
 * ids fall back to a deterministic token.
 */
class FakeStringResources : StringResources {
    private val values = mapOf(
        R.string.catalog_offline_no_accounts to
            "Can't reach your Budget server, so there are no accounts to choose from yet.",
        R.string.capture_no_accounts to
            "Add an account in Budget on Nextcloud to start saving transactions.",
        R.string.amount_hint to "Enter an amount like 24.31",
        R.string.save_in_flight to "Still saving this transaction…",
        R.string.save_queued to
            "Couldn't reach your server. This transaction is queued and will be sent automatically.",
        R.string.review_split_unavailable to
            "Can't split this by item — the items don't add up to the total.",
        R.string.review_extract_failed to "Couldn't read this receipt — enter the details below.",
        R.string.capture_ocr_unavailable to
            "Receipt scanning isn't set up on your Budget server — see Budget's settings in Nextcloud.",
        R.string.review_split_saved_error to "Saved, but couldn't split it by item.",
        R.string.onboarding_no_browser to "No browser is available to finish signing in.",
        R.string.onboarding_server_unreachable to
            "Couldn't reach that server. Check the address and try again.",
        R.string.overview_accounts_other to "Other",
        R.string.activity_error_offline to "Couldn't reach your Budget server.",
        R.string.activity_error_generic to "Something went wrong loading your activity.",
    )

    override fun get(id: Int): String = values[id] ?: "string:$id"

    override fun get(id: Int, vararg formatArgs: Any): String =
        (values[id] ?: "string:$id").let { if (formatArgs.isEmpty()) it else it.format(*formatArgs) }
}
