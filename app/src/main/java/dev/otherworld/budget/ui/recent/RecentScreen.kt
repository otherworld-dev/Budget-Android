package dev.otherworld.budget.ui.recent

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.domain.model.RecentTransaction
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Read-only view of transactions already saved on the server -- nothing here edits data.
 * Tapping a row opens the same transaction in the Budget web UI, which is where editing
 * happens. A failed refresh shows a dismissible banner but keeps whatever rows are already
 * on screen, so a dropped connection never blanks a list the user is mid-read of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    onBack: () -> Unit,
    viewModel: RecentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // refresh() always clears error to null before a new attempt, so keying the reset on
    // uiState.error itself makes a dismissed banner reappear for a genuinely new failure --
    // even one with identical text -- without an explicit "dismiss" concept in the ViewModel.
    var errorDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.error) { errorDismissed = false }

    // Not every Android device has a browser (kiosk builds, stripped e-ink readers -- this app's
    // own reference device is one), and an unguarded ACTION_VIEW there throws
    // ActivityNotFoundException and takes the app down. Reported in the same banner a refresh
    // failure uses rather than crashing or, worse, silently doing nothing.
    var openFailed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.loading,
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
                        message = stringResource(R.string.recent_no_opener),
                        onDismiss = { openFailed = false },
                    )
                }

                if (uiState.transactions.isEmpty()) {
                    // Not shown mid-initial-load: without this, the pull-to-refresh spinner
                    // and "nothing saved" text would flash together for anyone opening the
                    // screen for the first time, before the first fetch has even returned.
                    if (!uiState.loading) EmptyState()
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.transactions, key = { it.id }) { transaction ->
                            TransactionRow(
                                transaction = transaction,
                                webUrl = viewModel.webUrlFor(transaction.id),
                                onOpen = { url ->
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                    } catch (e: ActivityNotFoundException) {
                                        openFailed = true
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Not clickable when [webUrl] is null -- a dead tap (no credentials) is worse than an inert row. */
@Composable
private fun TransactionRow(
    transaction: RecentTransaction,
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
        // weight(1f) + ellipsis so a long merchant name is truncated rather than pushing the
        // amount off-screen; the amount keeps its intrinsic width and stays visible.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                transaction.merchant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.recent_row_subtitle,
                    transaction.date.format(dateFormatter),
                    transaction.accountName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            transaction.amount.format(),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.recent_empty),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.recent_dismiss)) }
        }
    }
}
