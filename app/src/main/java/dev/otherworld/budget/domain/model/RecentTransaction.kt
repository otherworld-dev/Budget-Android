package dev.otherworld.budget.domain.model

import java.time.LocalDate

data class RecentTransaction(
    val id: Long,
    val merchant: String,
    val date: LocalDate,
    val amount: Money,
    val accountName: String,
)
