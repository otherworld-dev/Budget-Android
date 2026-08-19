package dev.otherworld.budget.ui.quickadd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.ui.components.AccountDropdown
import dev.otherworld.budget.ui.components.CategoryDropdown
import dev.otherworld.budget.ui.components.ReadOnlyDateField
import dev.otherworld.budget.ui.components.TransactionDatePickerDialog
import java.time.LocalDate

/**
 * Manual entry for a transaction that has no receipt to photograph -- reached from the Capture
 * screen's top bar, which is the app's only navigation surface (no bottom bar, no drawer).
 *
 * Deliberately the same field order, the same pickers and the same date behaviour as Review, so
 * the two screens are not two different ways to describe one transaction. What differs is what is
 * required: only amount and account. See [QuickAddUiState.canSave], and [QuickAddViewModel]'s
 * KDoc for why saving here goes through the capture queue rather than straight to the API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddScreen(
    viewModel: QuickAddViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // rememberSaveable so an open date picker survives a rotation instead of vanishing.
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quickadd_title)) },
                // Disabled while saving for exactly the reason Cancel below is: this is the same
                // onDone, so it pops the same back stack and parks the same row for review.
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !uiState.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // First and focusable, because it is the only thing the user came here to type; every
            // field below it can be left exactly as it arrived.
            OutlinedTextField(
                value = uiState.amountText,
                onValueChange = viewModel::onAmountChanged,
                label = { Text(stringResource(R.string.quickadd_amount)) },
                isError = uiState.amountError != null,
                supportingText = uiState.amountError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            AccountDropdown(
                accounts = uiState.accounts,
                selectedId = uiState.selectedAccountId,
                onSelected = viewModel::onAccountSelected,
            )

            // Gated on !loading so the "no accounts" copy never flashes up during the fetch that
            // is about to produce them. Plain supporting text under the field it explains, rather
            // than the tinted notice used for save outcomes -- this is a statement about the
            // picker, not about the user's money.
            if (!uiState.loading) {
                uiState.accountsMessage?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.merchant,
                onValueChange = viewModel::onMerchantChanged,
                label = { Text(stringResource(R.string.quickadd_merchant)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            ReadOnlyDateField(value = uiState.dateText, onClick = { showDatePicker = true })
            Spacer(Modifier.height(12.dp))

            CategoryDropdown(
                categories = uiState.categories,
                selectedId = uiState.selectedCategoryId,
                onSelected = viewModel::onCategorySelected,
            )

            // Same treatment as Review's: a tinted notice with its own bounds, not red body text,
            // because it is a statement about what happened to the transaction rather than a
            // validation error on a field the user has not filled in.
            uiState.saveError?.let { message ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(message, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Visible progress, not just a greyed-out Save. Offline or on a slow server, post()
            // sits for up to a 15s connect (or a 120s read) timeout with nothing on screen
            // changing, and a user who cannot tell a slow save from a hung one reaches for the
            // nearest way out. That way out used to be Cancel -- which pops the back stack,
            // destroys the nav entry, cancels viewModelScope, and tears down the in-flight
            // createTransaction. ReceiptRepository.post treats that teardown as an unknown
            // outcome -- the server may or may not have received the request -- and parks the
            // row in AWAITING_REVIEW with the check-Recent warning, so it surfaces straight
            // away in Capture's ready-to-review banner. Safe, but it makes Cancel a lie: the
            // button's visible promise is "stop, nothing has happened", and what it actually
            // delivers is "a transaction that may already be on your server, now waiting for
            // you to double-check it".
            //
            // So the two halves are one fix: say something is happening, and remove the buttons
            // that would end it. System Back is deliberately left working -- a 120s read timeout
            // is far too long to trap someone with no way out, and a Back-cancelled save leaves
            // a parked, reviewable row behind rather than losing anything.
            if (uiState.saving) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.quickadd_saving), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
            }

            // Once the entry is the queue's (a failed post left a durable row behind), there is
            // nothing left to do here but leave: pressing Save again would not retry it, it would
            // enqueue a second transaction. So the pair of buttons collapses to a single Done --
            // an affordance that cannot be misread as "try again", which a still-present Save
            // sitting under a red notice absolutely would be.
            if (uiState.handedToQueue) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDone) { Text(stringResource(R.string.quickadd_done)) }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // enabled = !saving: see the progress block above. Leaving Cancel live during
                    // a save gives the user a button whose visible promise ("stop, nothing has
                    // happened") is the opposite of what it does -- it abandons a request that
                    // may already be with the server, leaving the row it created parked for
                    // review with a warning to check Recent.
                    TextButton(onClick = onDone, enabled = !uiState.saving) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = viewModel::onSaveClicked, enabled = uiState.canSave) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        TransactionDatePickerDialog(
            currentValue = uiState.dateText,
            fallback = LocalDate.now(),
            onDismiss = { showDatePicker = false },
            onPicked = viewModel::onDateChanged,
        )
    }
}
