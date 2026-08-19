package dev.otherworld.budget.data.local

import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.SplitPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Persists a transaction's confirmed splits in the queue row. Never throws on malformed input. */
object SplitCodec {
    @Serializable private data class Snapshot(val currency: String, val parts: List<Part>)
    @Serializable private data class Part(val amount: String, val categoryId: Long? = null, val description: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(parts: List<SplitPart>): String = json.encodeToString(
        Snapshot(
            currency = parts.firstOrNull()?.amount?.currency ?: "GBP",
            parts = parts.map { Part(it.amount.amount.toPlainString(), it.categoryId, it.description) },
        )
    )

    fun decode(raw: String): List<SplitPart>? = runCatching {
        val s = json.decodeFromString<Snapshot>(raw)
        s.parts.map { SplitPart(Money(BigDecimal(it.amount), s.currency), it.categoryId, it.description) }
    }.getOrNull()
}
