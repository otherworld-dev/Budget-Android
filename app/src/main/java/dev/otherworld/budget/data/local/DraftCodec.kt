package dev.otherworld.budget.data.local

import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.domain.model.LineItem
import dev.otherworld.budget.domain.model.Money
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

/** Persists a [DraftTransaction] in the queue row. Never throws on malformed input. */
object DraftCodec {

    @Serializable private data class Snapshot(
        val merchant: String? = null,
        val date: String? = null,
        val total: String? = null,
        val currency: String? = null,
        val suggestedCategoryId: Long? = null,
        val lineItems: List<Line> = emptyList(),
        val subtotal: String? = null,
        val tax: String? = null,
        val discount: String? = null,
    )
    @Serializable private data class Line(val description: String, val amount: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(draft: DraftTransaction): String = json.encodeToString(
        Snapshot(
            merchant = draft.merchant,
            date = draft.date?.toString(),
            total = draft.total?.amount?.toPlainString(),
            currency = draft.total?.currency,
            suggestedCategoryId = draft.suggestedCategoryId,
            lineItems = draft.lineItems.map { Line(it.description, it.amount?.amount?.toPlainString()) },
            subtotal = draft.subtotal?.amount?.toPlainString(),
            tax = draft.tax?.amount?.toPlainString(),
            discount = draft.discount?.amount?.toPlainString(),
        )
    )

    fun decode(raw: String): DraftTransaction? = runCatching {
        val snapshot = json.decodeFromString<Snapshot>(raw)
        val currency = snapshot.currency
        val hasMoney = snapshot.total != null || snapshot.subtotal != null ||
            snapshot.tax != null || snapshot.discount != null ||
            snapshot.lineItems.any { it.amount != null }
        // FallbackCurrencyCache is deliberate that a currency is never guessed: inventing one
        // (the old "GBP" default) could mis-label and, via createTransaction, mis-save an amount.
        // A persisted row carrying money but no currency is therefore malformed -- drop it (return
        // null, the same way genuinely unparseable input is dropped) rather than mislabel it. When
        // there is no money, the missing currency labels nothing and the draft still decodes.
        if (hasMoney && currency == null) return@runCatching null
        fun money(value: String?): Money? = value?.let { Money(BigDecimal(it), currency!!) }
        DraftTransaction(
            merchant = snapshot.merchant,
            date = snapshot.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            total = money(snapshot.total),
            suggestedCategoryId = snapshot.suggestedCategoryId,
            lineItems = snapshot.lineItems.map { LineItem(it.description, money(it.amount)) },
            subtotal = money(snapshot.subtotal),
            tax = money(snapshot.tax),
            discount = money(snapshot.discount),
        )
    }.getOrNull()
}
