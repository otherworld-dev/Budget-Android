package dev.otherworld.budget.ui.overview

import androidx.annotation.StringRes
import dev.otherworld.budget.R
import java.util.Locale

/**
 * The heading string for an [dev.otherworld.budget.domain.model.Account.type] -- the server sends
 * its raw enum value (`credit_card`), which is no heading to show anyone. Covers every type the
 * Budget server defines (its `AccountType` enum); a blank type (an older server, or a genuinely
 * untyped account) is "Other". Null for anything else, which [humaniseAccountType] then words.
 */
@StringRes
internal fun accountTypeLabelRes(type: String): Int? = when (type.trim()) {
    "" -> R.string.overview_accounts_other
    "checking" -> R.string.account_type_checking
    "savings" -> R.string.account_type_savings
    "credit_card" -> R.string.account_type_credit_card
    "investment" -> R.string.account_type_investment
    "loan" -> R.string.account_type_loan
    "cash" -> R.string.account_type_cash
    "money_market" -> R.string.account_type_money_market
    "cryptocurrency" -> R.string.account_type_cryptocurrency
    "mortgage" -> R.string.account_type_mortgage
    "line_of_credit" -> R.string.account_type_line_of_credit
    else -> null
}

/**
 * A type this app doesn't know yet (a newer server's) still gets a readable heading rather than
 * the raw value: `store_card` reads "Store card". Untranslated, but better than an enum name.
 */
internal fun humaniseAccountType(type: String): String =
    type.trim().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
