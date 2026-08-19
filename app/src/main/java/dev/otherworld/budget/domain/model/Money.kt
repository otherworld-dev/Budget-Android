package dev.otherworld.budget.domain.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

data class Money(val amount: BigDecimal, val currency: String) {

    fun format(locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { this.currency = Currency.getInstance(this@Money.currency) }
        }.format(amount)

    companion object {
        /**
         * Lenient parse for both server payloads ("24.31") and user typing ("£1,234.56",
         * "24,31", "-5.00" for a refund). Returns null rather than throwing -- an unreadable
         * total is a validation state the review screen renders, not an exception.
         *
         * A single leading '-' is honoured and carried through to the resulting
         * [BigDecimal] (a refund/return has a negative amount); a '-' anywhere else in the
         * input -- trailing, in the middle, or doubled up -- is treated as unparseable, as
         * is a bare "-" with no digits.
         *
         * Everything but digits, '.', ',' and a leading '-' is discarded, then the
         * surviving separators are split into segments. The *last* separator is the decimal
         * point only if it is followed by 1-2 digits; every separator before it must be a
         * thousands grouping, which means it is followed by exactly 3 digits before the next
         * separator. If that grouping rule fails anywhere -- e.g. "12.34.56", where the
         * first '.' is followed by only 2 digits -- the input is rejected rather than
         * guessed at.
         */
        fun parse(raw: String, currency: String): Money? {
            val stripped = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
            if (stripped.isEmpty()) return null

            val negative = stripped.startsWith("-")
            val digits = if (negative) stripped.substring(1) else stripped
            if (digits.isEmpty() || digits.contains('-')) return null

            val separators = digits.indices.filter { digits[it] == '.' || digits[it] == ',' }

            val normalisedDigits = if (separators.isEmpty()) {
                digits
            } else {
                val bounds = listOf(-1) + separators + listOf(digits.length)
                val segments = (0 until bounds.size - 1).map { i -> digits.substring(bounds[i] + 1, bounds[i + 1]) }

                val fraction = segments.last()
                val groups = segments.subList(1, segments.size - 1)
                val whole = segments.first() + groups.joinToString("")

                if (fraction.length !in 1..2) return null
                if (groups.any { it.length != 3 }) return null
                if (whole.isEmpty()) return null

                "$whole.$fraction"
            }

            val normalised = if (negative) "-$normalisedDigits" else normalisedDigits
            return runCatching { BigDecimal(normalised) }.getOrNull()?.let { Money(it, currency) }
        }
    }
}
