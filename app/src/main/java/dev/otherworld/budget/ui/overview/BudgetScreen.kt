package dev.otherworld.budget.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.ui.common.openInBrowser

/**
 * All expense categories with a budget, sorted the same way as Overview's compact "at risk" card
 * (overspent first, then lowest `remaining / budgeted` -- see [BudgetOrdering.byRisk]). Reached
 * only from Overview's "See all" link, sharing its [OverviewViewModel] rather than fetching
 * again: see [dev.otherworld.budget.ui.nav.BudgetNavHost] for how that instance is scoped to the
 * Overview back-stack entry. No pull-to-refresh of its own -- Overview already owns that, and
 * this screen is a read-only expansion of exactly the same [CheckRepository] state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    onBack: () -> Unit,
    viewModel: OverviewViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val budget = uiState.budget
    val context = LocalContext.current

    // Same no-browser guard as Overview's own rows (ui/common/OpenInBrowser.kt) -- rows here are
    // tappable too (spec §2.2, review fix round 1 finding 3), so this screen needs it independently
    // rather than relying on Overview's, which is a different composable entirely.
    var openFailed by remember { mutableStateOf(false) }
    fun open(url: String?) {
        url ?: return
        if (!context.openInBrowser(url)) openFailed = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overview_budget_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val data = budget.data
                when {
                    data != null -> data.byRisk().forEach { line ->
                        BudgetDetailRow(line, onOpen = { open(viewModel.budgetUrl()) })
                    }
                    // Same "part-upgraded server" case OverviewScreen's SectionBody handles --
                    // this screen has its own inline state handling rather than SectionBody
                    // itself, since it renders only the one section, not three.
                    budget.unsupported -> Text(
                        stringResource(R.string.overview_section_unsupported),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    budget.refreshing -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                    }
                    budget.error != null -> Text(budget.error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One category's full detail: spent-of-budgeted, remaining, and a bar (spec §2.2). Tapping it
 *  opens the Budget web page, same as Overview's own compact row for the same category. */
@Composable
private fun BudgetDetailRow(line: BudgetLine, onOpen: () -> Unit) {
    val negative = line.remaining.amount.signum() < 0
    val color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Text(line.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.overview_budget_line, line.spent.format(), line.budgeted.format()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (negative) stringResource(R.string.overview_budget_over, line.remaining.abs().format()) else line.remaining.format(),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        LinearProgressIndicator(
            progress = { progressFraction(line.spent, line.budgeted) },
            color = color,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
