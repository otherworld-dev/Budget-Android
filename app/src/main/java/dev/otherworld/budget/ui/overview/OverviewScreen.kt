package dev.otherworld.budget.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.ui.common.openInBrowser
import java.math.MathContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Balances, budget left this month and bills due soon, each its own section (spec §2.2). A
 * failed refresh never blanks a section that already has data -- see [SectionUi]'s own KDoc --
 * so this mirrors [dev.otherworld.budget.ui.activity.ActivityScreen]'s shape: `onVisible` on
 * first composition and every resume, pull-to-refresh always forces one, and a tap on a section
 * header opens the matching Budget web page, guarded the same way against a device with no
 * browser at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenSettings: () -> Unit,
    onOpenBudgetDetail: () -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.onVisible() }
    LifecycleResumeEffect(Unit) {
        viewModel.onVisible()
        onPauseOrDispose { }
    }

    var openFailed by remember { mutableStateOf(false) }
    fun open(url: String?) {
        url ?: return
        if (!context.openInBrowser(url)) openFailed = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overview_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (openFailed) {
                ErrorBanner(
                    message = stringResource(R.string.activity_no_opener),
                    onDismiss = { openFailed = false },
                )
            }
            OverviewContent(
                state = uiState,
                onRefresh = viewModel::refresh,
                onOpenBalances = { open(viewModel.accountsUrl()) },
                onOpenBudget = { open(viewModel.budgetUrl()) },
                onOpenBills = { open(viewModel.billsUrl()) },
                onSeeAllBudget = onOpenBudgetDetail,
            )
        }
    }
}

/**
 * Stateless so [OverviewScreenTest] can render every state (data, old server, per-section error)
 * from a fixed [OverviewUiState] with no ViewModel, Hilt or navigation involved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewContent(
    state: OverviewUiState,
    onRefresh: () -> Unit,
    onOpenBalances: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenBills: () -> Unit,
    onSeeAllBudget: () -> Unit,
) {
    val isRefreshing = !state.oldServer &&
        (state.balances.refreshing || state.budget.refreshing || state.bills.refreshing)

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.oldServer) {
            // One explanatory state, not the sections (spec §2.2) -- balances/recent would still
            // work on a server this old, but showing a partial screen isn't what the spec asks for.
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.overview_update_server), textAlign = TextAlign.Center)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BalancesSection(state.balances, onOpenBalances, onRefresh)
                HorizontalDivider()
                BudgetSection(state.budget, state.atRisk, onOpenBudget, onRefresh, onSeeAllBudget)
                HorizontalDivider()
                BillsSection(state.bills, onOpenBills, onRefresh)
            }
        }
    }
}

@Composable
private fun BalancesSection(section: SectionUi<List<AccountGroup>>, onHeaderClick: () -> Unit, onRetry: () -> Unit) {
    SectionHeader(stringResource(R.string.overview_balances_title), onHeaderClick)
    SectionBody(section, onRetry) { groups ->
        Column {
            groups.forEach { group ->
                Text(
                    group.type,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                group.accounts.forEach { account -> AccountRow(account) }
            }
        }
    }
}

@Composable
private fun AccountRow(account: Account) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            if (account.shared) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.Person,
                    contentDescription = stringResource(R.string.overview_shared),
                    modifier = Modifier.height(16.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(account.balance?.format() ?: "—", style = MaterialTheme.typography.bodyLarge)
            account.balanceInBase?.let {
                Text(
                    it.format(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BudgetSection(
    section: SectionUi<BudgetStatus>,
    atRisk: List<BudgetLine>,
    onHeaderClick: () -> Unit,
    onRetry: () -> Unit,
    onSeeAll: () -> Unit,
) {
    SectionHeader(stringResource(R.string.overview_budget_title), onHeaderClick)
    SectionBody(section, onRetry) { budget ->
        Column {
            Text(
                stringResource(R.string.overview_budget_left, budget.remaining.format(), budget.budgeted.format()),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { progressFraction(budget.spent, budget.budgeted) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            if (budget.startDate.dayOfMonth != 1) {
                Text(
                    stringResource(
                        R.string.overview_budget_range,
                        budget.startDate.format(dateFormatter),
                        budget.endDate.format(dateFormatter),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            atRisk.forEach { line -> AtRiskRow(line) }
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.overview_see_all)) }
        }
    }
}

/** The compact "closest to running out" row on Overview -- see [BudgetDetailRow] for the full one. */
@Composable
internal fun AtRiskRow(line: BudgetLine) {
    val negative = line.remaining.amount.signum() < 0
    val color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(line.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (negative) {
                stringResource(R.string.overview_budget_over, line.remaining.abs().format())
            } else {
                stringResource(R.string.overview_budget_line, line.spent.format(), line.budgeted.format())
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        LinearProgressIndicator(
            progress = { progressFraction(line.spent, line.budgeted) },
            color = color,
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun BillsSection(section: SectionUi<List<BillRow>>, onHeaderClick: () -> Unit, onRetry: () -> Unit) {
    SectionHeader(stringResource(R.string.overview_bills_title), onHeaderClick)
    SectionBody(section, onRetry) { rows ->
        if (rows.isEmpty()) {
            Text(stringResource(R.string.overview_bills_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column { rows.forEach { row -> BillItemRow(row) } }
        }
    }
}

@Composable
private fun BillItemRow(row: BillRow) {
    val overdue = row.label == DueLabel.Overdue
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(row.bill.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                dueLabelText(row.label),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(row.bill.amount.format(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun dueLabelText(label: DueLabel): String = when (label) {
    DueLabel.Overdue -> stringResource(R.string.overview_due_overdue)
    DueLabel.Today -> stringResource(R.string.overview_due_today)
    DueLabel.Tomorrow -> stringResource(R.string.overview_due_tomorrow)
    is DueLabel.InDays -> pluralStringResource(R.plurals.overview_due_in_days, label.days, label.days)
}

/** A tappable section title -- opens the section's own Budget web page (see [OverviewViewModel]'s URLs). */
@Composable
private fun SectionHeader(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * The three per-section states the brief asks for: data (plus staleness underneath), an error
 * with no data (message + Retry), or a bare refresh with no data yet (a small spinner). An
 * unsupported section with neither data nor error (a part-upgraded server missing just this one
 * route) renders nothing further -- there's nothing wrong to report, just nothing to show yet.
 */
@Composable
private fun <T> SectionBody(section: SectionUi<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val data = section.data
        if (data != null) {
            content(data)
            section.staleness?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else if (section.error != null) {
            Text(section.error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_try_again)) }
        } else if (section.refreshing) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp).padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.activity_dismiss)) }
        }
    }
}

/** [Money] has no unary minus/`abs` of its own; shared with [BudgetScreen]'s own rows. */
internal fun Money.abs(): Money = copy(amount = amount.abs())

/**
 * `spent / budgeted`, exact [BigDecimal] division, never a raw [Double] (see [Money]). A zero or
 * negative budgeted amount can't be divided by -- shown as an empty bar rather than crashing or
 * showing full, since "no budget set" isn't the same as "fully spent".
 */
internal fun progressFraction(spent: Money, budgeted: Money): Float {
    if (budgeted.amount.signum() <= 0) return 0f
    return spent.amount.divide(budgeted.amount, MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f)
}
