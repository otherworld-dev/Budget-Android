package dev.otherworld.budget.data.local

import dev.otherworld.budget.data.remote.Capabilities
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.Category
import dev.otherworld.budget.domain.model.Direction
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.PeriodToDate
import dev.otherworld.budget.domain.model.RecentTransaction
import dev.otherworld.budget.domain.model.SplitLine
import dev.otherworld.budget.domain.model.TransferLink
import dev.otherworld.budget.domain.model.UpcomingBill
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Persists each [SnapshotKind]'s payload as JSON. Follows [DraftCodec]'s pattern -- private
 * `@Serializable` mirrors of the domain types, money as plain strings, dates as ISO -- but unlike
 * [DraftCodec] there is no single shared currency to lean on: [Account.balanceInBase], for
 * instance, is deliberately in a different currency than [Account.balance], so every [Money] here
 * carries its own currency alongside the amount (see [MoneyDto]). `decode*` never throws;
 * malformed input decodes to null.
 */
object SnapshotCodec {

    @Serializable private data class MoneyDto(val amount: String, val currency: String)
    private fun Money.toDto() = MoneyDto(amount.toPlainString(), currency)
    private fun MoneyDto.toDomain() = Money(BigDecimal(amount), currency)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- Accounts ------------------------------------------------------------------------------

    @Serializable private data class AccountDto(
        val id: Long,
        val name: String,
        val currency: String,
        val type: String = "",
        val balance: MoneyDto? = null,
        val balanceInBase: MoneyDto? = null,
        val closed: Boolean = false,
        val shared: Boolean = false,
    )

    private fun Account.toDto() = AccountDto(
        id, name, currency, type, balance?.toDto(), balanceInBase?.toDto(), closed, shared,
    )
    private fun AccountDto.toDomain() = Account(
        id, name, currency, type, balance?.toDomain(), balanceInBase?.toDomain(), closed, shared,
    )

    fun encodeAccounts(v: List<Account>): String = json.encodeToString(v.map { it.toDto() })
    fun decodeAccounts(raw: String): List<Account>? = runCatching {
        json.decodeFromString<List<AccountDto>>(raw).map { it.toDomain() }
    }.getOrNull()

    // -- Categories ------------------------------------------------------------------------------

    @Serializable private data class CategoryDto(val id: Long, val name: String, val parentId: Long?)

    private fun Category.toDto() = CategoryDto(id, name, parentId)
    private fun CategoryDto.toDomain() = Category(id, name, parentId)

    fun encodeCategories(v: List<Category>): String = json.encodeToString(v.map { it.toDto() })
    fun decodeCategories(raw: String): List<Category>? = runCatching {
        json.decodeFromString<List<CategoryDto>>(raw).map { it.toDomain() }
    }.getOrNull()

    // -- Capabilities ------------------------------------------------------------------------------

    @Serializable private data class CapabilitiesDto(
        val ocrAvailable: Boolean,
        val currency: String,
        val version: String,
        val splitsAvailable: Boolean,
        val checkAvailable: Boolean = false,
    )

    private fun Capabilities.toDto() =
        CapabilitiesDto(ocrAvailable, currency, version, splitsAvailable, checkAvailable)
    private fun CapabilitiesDto.toDomain() =
        Capabilities(ocrAvailable, currency, version, splitsAvailable, checkAvailable)

    fun encodeCapabilities(v: Capabilities): String = json.encodeToString(v.toDto())
    fun decodeCapabilities(raw: String): Capabilities? = runCatching {
        json.decodeFromString<CapabilitiesDto>(raw).toDomain()
    }.getOrNull()

    // -- Budget status ------------------------------------------------------------------------------

    @Serializable private data class BudgetLineDto(
        val categoryId: Long,
        val name: String,
        val parentId: Long?,
        val type: String,
        val period: String,
        val budgeted: MoneyDto,
        val carried: MoneyDto,
        val spent: MoneyDto,
        val remaining: MoneyDto,
        val shared: Boolean,
        // Defaulted: a budget cached by an app version without it still decodes.
        val periodToDate: PeriodToDateDto? = null,
    )

    @Serializable private data class PeriodToDateDto(val budgeted: MoneyDto, val spent: MoneyDto)

    private fun BudgetLine.toDto() = BudgetLineDto(
        categoryId, name, parentId, type, period,
        budgeted.toDto(), carried.toDto(), spent.toDto(), remaining.toDto(), shared,
        periodToDate?.let { PeriodToDateDto(it.budgeted.toDto(), it.spent.toDto()) },
    )
    private fun BudgetLineDto.toDomain() = BudgetLine(
        categoryId, name, parentId, type, period,
        budgeted.toDomain(), carried.toDomain(), spent.toDomain(), remaining.toDomain(), shared,
        periodToDate?.let { PeriodToDate(it.budgeted.toDomain(), it.spent.toDomain()) },
    )

    @Serializable private data class BudgetStatusDto(
        val month: String,
        val startDate: String,
        val endDate: String,
        val currency: String,
        val budgeted: MoneyDto,
        val spent: MoneyDto,
        val remaining: MoneyDto,
        val lines: List<BudgetLineDto>,
    )

    private fun BudgetStatus.toDto() = BudgetStatusDto(
        month, startDate.toString(), endDate.toString(), currency,
        budgeted.toDto(), spent.toDto(), remaining.toDto(), lines.map { it.toDto() },
    )
    private fun BudgetStatusDto.toDomain() = BudgetStatus(
        month, LocalDate.parse(startDate), LocalDate.parse(endDate), currency,
        budgeted.toDomain(), spent.toDomain(), remaining.toDomain(), lines.map { it.toDomain() },
    )

    fun encodeBudget(v: BudgetStatus): String = json.encodeToString(v.toDto())
    fun decodeBudget(raw: String): BudgetStatus? = runCatching {
        json.decodeFromString<BudgetStatusDto>(raw).toDomain()
    }.getOrNull()

    // -- Upcoming bills ------------------------------------------------------------------------------

    @Serializable private data class UpcomingBillDto(
        val id: Long,
        val name: String,
        val amount: MoneyDto,
        val nextDueDate: String,
        val overdue: Boolean,
        val frequency: String,
        val accountName: String?,
        val isTransfer: Boolean,
        val autoPay: Boolean,
        val shared: Boolean,
        // Defaulted so a row cached before the flag existed still decodes (as exact).
        val estimated: Boolean = false,
    )

    private fun UpcomingBill.toDto() = UpcomingBillDto(
        id, name, amount.toDto(), nextDueDate.toString(), overdue, frequency,
        accountName, isTransfer, autoPay, shared, estimated,
    )
    private fun UpcomingBillDto.toDomain() = UpcomingBill(
        id, name, amount.toDomain(), LocalDate.parse(nextDueDate), overdue, frequency,
        accountName, isTransfer, autoPay, shared, estimated,
    )

    fun encodeBills(v: List<UpcomingBill>): String = json.encodeToString(v.map { it.toDto() })
    fun decodeBills(raw: String): List<UpcomingBill>? = runCatching {
        json.decodeFromString<List<UpcomingBillDto>>(raw).map { it.toDomain() }
    }.getOrNull()

    // -- Recent transactions ------------------------------------------------------------------------------

    @Serializable private data class SplitLineDto(val amount: MoneyDto, val categoryName: String?, val description: String?)
    private fun SplitLine.toDto() = SplitLineDto(amount.toDto(), categoryName, description)
    private fun SplitLineDto.toDomain() = SplitLine(amount.toDomain(), categoryName, description)

    @Serializable private data class TransferLinkDto(val linkedTransactionId: Long, val linkedAccountName: String?)
    private fun TransferLink.toDto() = TransferLinkDto(linkedTransactionId, linkedAccountName)
    private fun TransferLinkDto.toDomain() = TransferLink(linkedTransactionId, linkedAccountName)

    @Serializable private data class RecentTransactionDto(
        val id: Long,
        val merchant: String,
        val date: String,
        val amount: MoneyDto,
        val accountName: String,
        val accountId: Long? = null,
        val direction: String = Direction.UNKNOWN.name,
        val categoryName: String? = null,
        val splits: List<SplitLineDto> = emptyList(),
        val transfer: TransferLinkDto? = null,
    )

    private fun RecentTransaction.toDto() = RecentTransactionDto(
        id, merchant, date.toString(), amount.toDto(), accountName, accountId, direction.name,
        categoryName, splits.map { it.toDto() }, transfer?.toDto(),
    )
    private fun RecentTransactionDto.toDomain() = RecentTransaction(
        id, merchant, LocalDate.parse(date), amount.toDomain(), accountName, accountId,
        // An unrecognised value (never written by this codec, but a defensive fallback all the
        // same) reads back as UNKNOWN, the same as an older server's row that never sent a type.
        runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.UNKNOWN),
        categoryName, splits.map { it.toDomain() }, transfer?.toDomain(),
    )

    fun encodeRecent(v: List<RecentTransaction>): String = json.encodeToString(v.map { it.toDto() })
    fun decodeRecent(raw: String): List<RecentTransaction>? = runCatching {
        json.decodeFromString<List<RecentTransactionDto>>(raw).map { it.toDomain() }
    }.getOrNull()
}
