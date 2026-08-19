package dev.otherworld.budget.data.remote.dto

import dev.otherworld.budget.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class CapabilitiesDto(
    @SerialName("ocr_available") val ocrAvailable: Boolean,
    val currency: String,
    val version: String,
    // Absent on older servers; absence means the server does not accept splits.
    @SerialName("splits_available") val splitsAvailable: Boolean = false,
)

/**
 * The slice of Nextcloud *core*'s `GET /ocs/v2.php/cloud/capabilities` this app reads: the
 * theming colour. This is a standard core OCS route (not part of the Budget app contract), so it
 * answers 200 on any modern Nextcloud even where the Budget routes 404. Every other field the
 * route returns -- and there are many -- is dropped by `ignoreUnknownKeys`. Each level is nullable
 * so an older server, a theming-disabled instance, or a missing `color` simply yields `null`
 * rather than a parse failure; the caller then keeps the default colour.
 */
@Serializable data class CoreCapabilitiesDto(val capabilities: CoreCapabilities? = null)
@Serializable data class CoreCapabilities(val theming: ThemingDto? = null)
@Serializable data class ThemingDto(val color: String? = null)

@Serializable data class AccountDto(val id: Long, val name: String, val currency: String) {
    fun toDomain() = Account(id, name, currency)
}

@Serializable data class CategoryDto(
    val id: Long,
    val name: String,
    @SerialName("parent_id") val parentId: Long? = null,
) { fun toDomain() = Category(id, name, parentId) }

@Serializable data class LineItemDto(val description: String, val amount: String? = null) {
    fun toDomain(currency: String) = LineItem(description, amount?.let { Money.parse(it, currency) })
}

@Serializable data class DraftDto(
    val merchant: String? = null,
    val date: String? = null,
    val total: String? = null,
    val currency: String? = null,
    @SerialName("suggested_category_id") val suggestedCategoryId: Long? = null,
    @SerialName("line_items") val lineItems: List<LineItemDto> = emptyList(),
    val subtotal: String? = null,
    val tax: String? = null,
    val discount: String? = null,
) {
    fun toDomain(fallbackCurrency: String): DraftTransaction {
        val ccy = currency ?: fallbackCurrency
        return DraftTransaction(
            merchant = merchant,
            date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            total = total?.let { Money.parse(it, ccy) },
            suggestedCategoryId = suggestedCategoryId,
            lineItems = lineItems.map { it.toDomain(ccy) },
            subtotal = subtotal?.let { Money.parse(it, ccy) },
            tax = tax?.let { Money.parse(it, ccy) },
            discount = discount?.let { Money.parse(it, ccy) },
        )
    }
}

@Serializable data class RecentDto(
    val id: Long,
    val merchant: String,
    val date: String,
    val amount: String,
    val currency: String,
    @SerialName("account_name") val accountName: String,
) {
    fun toDomain(): RecentTransaction? {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val parsedAmount = Money.parse(amount, currency) ?: return null
        return RecentTransaction(id, merchant, parsedDate, parsedAmount, accountName)
    }
}

/**
 * [idempotencyKey] is the server echoing back the key it accepted for this transaction, so the
 * client can prove the key was seen rather than infer it from later behaviour. Nullable: an older
 * server build simply omits it, and its absence is not treated as an error --
 * [dev.otherworld.budget.data.remote.BudgetApiRetrofit] only reacts when a *present* echo disagrees
 * with the key it sent.
 */
@Serializable data class CreatedDto(
    val id: Long,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    // Present when the transaction was recorded but its per-item splits were rejected (e.g. the
    // split amounts did not sum to the total). Absent -- and so null -- whenever there was no split
    // problem, which is always until splits are actually sent. The 201 also now carries `splits`,
    // `is_split` and `photo_error`, which stay unmodelled: the Retrofit Json ignores unknown keys.
    @SerialName("splits_error") val splitsError: String? = null,
)

@Serializable data class ErrorDataDto(@SerialName("error_code") val errorCode: String? = null)

/**
 * The wire shape of one entry in the `splits` JSON-array part of `POST transactions`
 * (`docs/server-api-contract.md`). Encoded with `explicitNulls = false` in
 * [dev.otherworld.budget.data.remote.BudgetApiRetrofit], so a null [categoryId] or [description] is
 * omitted from the JSON entirely -- that omission, not a `null` literal, is how the tax line carries
 * no category.
 */
@Serializable data class SplitWireDto(
    val amount: String,
    @SerialName("category_id") val categoryId: Long? = null,
    val description: String? = null,
)
