package dev.otherworld.budget.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.domain.model.Direction
import dev.otherworld.budget.domain.model.RecentTransaction
import dev.otherworld.budget.domain.model.SplitLine
import dev.otherworld.budget.ui.common.openInBrowser
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Read-only view of transactions already saved on the server -- nothing here edits data.
 * Tapping a row opens the same transaction in the Budget web UI, which is where editing
 * happens. A failed refresh shows a dismissible banner but keeps whatever rows are already
 * on screen, so a dropped connection never blanks a list the user is mid-read of.
 *
 * No back arrow in the top bar -- this is a bottom-bar tab, not a pushed screen -- but it does
 * get its own Settings action, same as Capture's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onOpenSettings: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The resume effect also fires on first composition, so this one call covers both: a
    // LaunchedEffect alongside it made every first visit refresh twice.
    LifecycleResumeEffect(Unit) {
        viewModel.onVisible()
        onPauseOrDispose { }
    }

    // refresh() clears/replaces the error on every attempt, so keying the reset on uiState.error
    // itself makes a dismissed banner reappear for a genuinely new failure -- even one with
    // identical text -- without an explicit "dismiss" concept in the ViewModel.
    var errorDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.error) { errorDismissed = false }

    // Not every Android device has a browser (kiosk builds, stripped e-ink readers -- this app's
    // own reference device is one); openInBrowser (ui/common, shared with Overview since Task 9)
    // guards that and reports failure here rather than crashing or, worse, silently doing nothing.
    var openFailed by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        if (!context.openInBrowser(url)) openFailed = true
    }

    // Ids of rows currently showing their split parts. rememberSaveable needs a Bundle-native
    // type to persist across a rotation/process death, which a bare Set is not -- the Saver
    // converts to/from LongArray only at that boundary.
    var expandedIds by rememberSaveable(stateSaver = ExpandedIdsSaver) { mutableStateOf(emptySet<Long>()) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.activity_title)) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                            )
                        }
                    },
                )
                uiState.staleness?.let { staleness ->
                    Text(
                        staleness,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val error = uiState.error
                if (error != null && !errorDismissed) {
                    ErrorBanner(message = error, onDismiss = { errorDismissed = true })
                }
                if (openFailed) {
                    ErrorBanner(
                        message = stringResource(R.string.activity_no_opener),
                        onDismiss = { openFailed = false },
                    )
                }

                if (uiState.rows.isEmpty()) {
                    // Not shown mid-initial-load: without this, the pull-to-refresh spinner
                    // and "nothing saved" text would flash together for anyone opening the
                    // screen for the first time, before the first fetch has even returned.
                    if (uiState.loaded) EmptyState()
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.rows, key = { it.key }) { row ->
                            when (row) {
                                is ActivityRow.Single -> SingleRow(
                                    tx = row.tx,
                                    webUrl = viewModel.webUrlFor(row.tx.id),
                                    expanded = row.tx.id in expandedIds,
                                    onToggleExpand = {
                                        expandedIds = if (row.tx.id in expandedIds) {
                                            expandedIds - row.tx.id
                                        } else {
                                            expandedIds + row.tx.id
                                        }
                                    },
                                    onOpen = ::openUrl,
                                )
                                is ActivityRow.TransferPair -> TransferPairRow(
                                    row = row,
                                    webUrl = viewModel.webUrlFor(row.from.id),
                                    onOpen = ::openUrl,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Not clickable when [webUrl] is null -- a dead tap (no credentials) is worse than an inert row.
 * The "Split" chip sits inside the same clickable row but consumes its own tap first, so tapping
 * it toggles [expanded] instead of opening the transaction.
 */
@Composable
private fun SingleRow(
    tx: RecentTransaction,
    webUrl: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (webUrl != null) base.clickable { onOpen(webUrl) } else base }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    singleRowTitle(tx),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tx.splits.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onToggleExpand,
                        label = { Text(stringResource(R.string.activity_split_chip)) },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            AmountText(amount = tx.amount.format(), isCredit = tx.direction == Direction.CREDIT)
        }
        Text(
            stringResource(R.string.activity_row_subtitle, tx.date.format(dateFormatter), tx.accountName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                tx.splits.forEach { part -> SplitPartRow(part) }
            }
        }
    }
}

/**
 * The row's title line when it's one half of a transfer whose partner isn't in this page of
 * results -- [tx.merchant] is what the *server* called the transaction (e.g. "Transfer to
 * Savings"), but the structured [dev.otherworld.budget.domain.model.TransferLink] is what the
 * spec asks the row to read instead, so the same wording holds regardless of what a given
 * server happens to name these.
 */
@Composable
private fun singleRowTitle(tx: RecentTransaction): String {
    val transfer = tx.transfer ?: return tx.merchant
    val linkedAccountName = transfer.linkedAccountName
        ?: return stringResource(R.string.activity_transfer)
    return if (tx.direction == Direction.DEBIT) {
        stringResource(R.string.activity_transfer_to, linkedAccountName)
    } else {
        stringResource(R.string.activity_transfer_from, linkedAccountName)
    }
}

/** Credits show "+£x" in the primary colour; debits and unknowns are unchanged from today. */
@Composable
private fun AmountText(amount: String, isCredit: Boolean) {
    if (isCredit) {
        Text(
            "+$amount",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    } else {
        Text(amount, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

@Composable
private fun SplitPartRow(part: SplitLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                part.categoryName ?: stringResource(R.string.activity_uncategorised),
                style = MaterialTheme.typography.bodyMedium,
            )
            part.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(part.amount.format(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Both halves visible, collapsed into one row: "From account -> To account" with the debit
 * side's amount, unsigned -- a transfer moving money is neither a credit nor a debit from the
 * user's point of view. Tapping opens [ActivityRow.TransferPair.from]'s own transaction.
 */
@Composable
private fun TransferPairRow(
    row: ActivityRow.TransferPair,
    webUrl: String?,
    onOpen: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (webUrl != null) base.clickable { onOpen(webUrl) } else base }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.activity_transfer_pair, row.from.accountName, row.to.accountName),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.from.date.format(dateFormatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(row.from.amount.format(), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.activity_empty),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.activity_dismiss)) }
        }
    }
}

private val ExpandedIdsSaver: Saver<Set<Long>, LongArray> = Saver(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)
