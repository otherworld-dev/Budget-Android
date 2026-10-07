package dev.otherworld.budget.domain.model

/**
 * [balance]/[balanceInBase] are null when the server did not send one at all -- an older
 * server's `GET /accounts` predates them -- rather than a zero balance, which is a real value
 * the app must not confuse with "unknown". [balanceInBase] is [balance] converted to the user's
 * base currency, only ever present alongside its own currency (see `AccountDto`). [type] is the
 * server's own grouping key ("checking", "savings", ...), used to group the Overview balances
 * list in server order. [closed] accounts are excluded from that list; [shared] flags one the
 * caller can see through Nextcloud sharing rather than owning outright.
 */
data class Account(
    val id: Long,
    val name: String,
    val currency: String,
    val type: String = "",
    val balance: Money? = null,
    val balanceInBase: Money? = null,
    val closed: Boolean = false,
    val shared: Boolean = false,
)
