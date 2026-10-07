package dev.otherworld.budget.ui.overview

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.UpcomingBill
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Exercises the stateless [OverviewContent] directly -- no ViewModel, Hilt or navigation -- against
 * fixed [OverviewUiState] values for the states the brief calls out: data, old server, and a
 * per-section error. Compile-checked only in this project (no emulator in this task's own
 * verification command), same as every other androidTest here.
 */
@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun money(v: String) = Money(BigDecimal(v), "GBP")

    private fun groceries() = BudgetLine(
        categoryId = 14,
        name = "Groceries",
        parentId = null,
        type = "expense",
        period = "monthly",
        budgeted = money("400.00"),
        carried = money("0.00"),
        spent = money("431.20"),
        remaining = money("-31.20"),
        shared = false,
    )

    private val dataState = OverviewUiState(
        balances = SectionUi(
            data = listOf(
                AccountGroup(
                    "checking",
                    listOf(Account(1, "Current Account", "GBP", type = "checking", balance = money("1234.56"))),
                ),
            ),
        ),
        budget = SectionUi(
            data = BudgetStatus(
                month = "2026-09",
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 30),
                currency = "GBP",
                budgeted = money("650.00"),
                spent = money("576.20"),
                remaining = money("73.80"),
                lines = listOf(groceries()),
            ),
        ),
        atRisk = listOf(groceries()),
        bills = SectionUi(
            data = listOf(
                BillRow(
                    UpcomingBill(
                        id = 1,
                        name = "Council Tax",
                        amount = money("150.00"),
                        nextDueDate = LocalDate.of(2026, 9, 20),
                        overdue = true,
                        frequency = "monthly",
                        accountName = "Current Account",
                        isTransfer = false,
                        autoPay = true,
                        shared = false,
                    ),
                    DueLabel.Overdue,
                ),
            ),
        ),
    )

    private val oldServerState = OverviewUiState(
        oldServer = true,
        budget = SectionUi(unsupported = true),
        bills = SectionUi(unsupported = true),
    )

    private val sectionErrorState = OverviewUiState(
        bills = SectionUi(error = "Couldn't reach your Budget server for this."),
    )

    // A part-upgraded server: budget alone 404s (unsupported, no data), while balances and bills
    // still answer normally -- oldServer stays false (that needs *both* budget and bills
    // unsupported), so the screen must still show the other two sections, not the whole-screen
    // old-server message (review fix round 1, finding 1).
    private val sectionUnsupportedState = OverviewUiState(
        balances = SectionUi(
            data = listOf(
                AccountGroup(
                    "checking",
                    listOf(Account(1, "Current Account", "GBP", type = "checking", balance = money("1234.56"))),
                ),
            ),
        ),
        budget = SectionUi(unsupported = true),
        bills = SectionUi(data = emptyList()),
    )

    private fun setContent(state: OverviewUiState) {
        composeRule.setContent {
            OverviewContent(
                state = state,
                onRefresh = {},
                onOpenBalances = {},
                onOpenBudget = {},
                onOpenBills = {},
                onSeeAllBudget = {},
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rendersBalancesBudgetAndBillsFromData() {
        setContent(dataState)

        composeRule.onNodeWithText("Current Account").assertIsDisplayed()
        composeRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeRule.onNodeWithText("Council Tax").assertIsDisplayed()
    }

    @Test
    fun oldServerShowsOnlyTheExplanatoryMessage() {
        setContent(oldServerState)

        composeRule.onNodeWithText(
            "Update Budget on your server to see balances, budget and bills here",
        ).assertIsDisplayed()
    }

    @Test
    fun aPerSectionErrorShowsItsMessageAndARetryButton() {
        setContent(sectionErrorState)

        composeRule.onNodeWithText("Couldn't reach your Budget server for this.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun anUnsupportedSectionWithNoDataExplainsItselfWithoutHidingTheOthers() {
        setContent(sectionUnsupportedState)

        composeRule.onNodeWithText("Update Budget on your server to see this here").assertIsDisplayed()
        // Not the whole-screen message -- only budget is affected, so the screen must not have
        // collapsed to the single old-server explanation.
        composeRule.onAllNodesWithText(
            "Update Budget on your server to see balances, budget and bills here",
        ).assertCountEquals(0)
        composeRule.onNodeWithText("Current Account").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing due in the next 14 days").assertIsDisplayed()
    }

    @Test
    fun accountBudgetAndBillRowsAreIndividuallyTappableNotJustTheHeader() {
        var balancesOpened = 0
        var budgetOpened = 0
        var billsOpened = 0
        composeRule.setContent {
            OverviewContent(
                state = dataState,
                onRefresh = {},
                onOpenBalances = { balancesOpened++ },
                onOpenBudget = { budgetOpened++ },
                onOpenBills = { billsOpened++ },
                onSeeAllBudget = {},
            )
        }
        composeRule.waitForIdle()

        // Spec §2.2 (review fix round 1, finding 3): an account row opens the accounts page, a
        // budget category row opens the budget page, and a bill row opens the bills page -- the
        // same three URLs the section headers themselves open, not a no-op tap.
        composeRule.onNodeWithText("Current Account").performClick()
        composeRule.onNodeWithText("Groceries").performClick()
        composeRule.onNodeWithText("Council Tax").performClick()

        assertEquals(1, balancesOpened)
        assertEquals(1, budgetOpened)
        assertEquals(1, billsOpened)
    }
}
