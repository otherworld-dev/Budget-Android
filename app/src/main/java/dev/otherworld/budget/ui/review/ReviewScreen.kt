package dev.otherworld.budget.ui.review

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.otherworld.budget.R
import dev.otherworld.budget.domain.model.Category
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.ui.components.AccountDropdown
import dev.otherworld.budget.ui.components.CategoryDropdown
import dev.otherworld.budget.ui.components.ReadOnlyDateField
import dev.otherworld.budget.ui.components.TransactionDatePickerDialog
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The core review-and-save screen: every capture ends here. When the connected server offers
 * splits ([ReviewUiState.splittable] -- the server supports them AND this receipt's items, tax
 * and total reconcile) the items section grows a "Split by item" [Switch]: turning it on swaps
 * the display-only list for [LineItemsOrSplitSection]'s per-row [CategoryDropdown]s and hides the
 * single whole-transaction category picker, since the parts carry the categorisation instead.
 * Everywhere else -- no splits capability, or a receipt whose items don't add up to the total --
 * the items render exactly as before, plain text with no controls.
 *
 * The photo block is conditional on [ReviewUiState.photoPath], which is null both for a Quick Add
 * manual entry (there never was one) and for a captured row whose file has since gone --
 * [ReviewViewModel] resolves both to null on load, so the screen omits the thumbnail and the
 * full-size viewer entirely rather than rendering an image that cannot load, and saves the same
 * either way.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            // A one-shot notice for a save that succeeded but whose splits the server rejected
            // (see ReviewUiState.postNotice) -- shown here, just before navigating away, so it
            // survives the screen closing rather than being attached to a Composable that is
            // about to leave composition.
            uiState.postNotice?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            onDone()
        }
    }

    // rememberSaveable so an open picker/confirm/full-image survives a rotation instead of vanishing.
    var showFullImage by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // enableEdgeToEdge() draws behind the status and navigation bars; without this the
            // Save/Discard row scrolled under the gesture/nav bar. safeDrawing also lifts the
            // fields above the IME.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // The draft (merchant/total) is available from the local row immediately, but the account
        // and category pickers are fetched from the server on open; a thin bar makes that fetch
        // visible instead of leaving the empty dropdowns looking like a dead end.
        if (uiState.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }

        uiState.photoPath?.let { path ->
            AsyncImage(
                model = path,
                contentDescription = stringResource(R.string.review_photo_desc),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clickable { showFullImage = true },
            )
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = uiState.merchant,
            onValueChange = viewModel::onMerchantChanged,
            label = { Text(stringResource(R.string.review_merchant)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        ReadOnlyDateField(value = uiState.dateText, onClick = { showDatePicker = true })
        Spacer(Modifier.height(12.dp))

        AccountDropdown(
            accounts = uiState.accounts,
            selectedId = uiState.selectedAccountId,
            onSelected = viewModel::onAccountSelected,
        )

        // Same treatment as Quick Add's picker note, and gated on !loading for the same reason:
        // the copy must never flash up during the fetch that is about to produce the accounts.
        // Without this the offline case was a silent dead end -- an empty dropdown and a Save
        // that never enables, unexplained. The failed-fetch case carries the one affordance
        // that can fix it from here: try the fetch again (which preserves the user's edits --
        // see ReviewViewModel.onRetryCatalogClicked).
        if (!uiState.loading) {
            uiState.accountsMessage?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (uiState.accountsRetryable) {
                    TextButton(onClick = viewModel::onRetryCatalogClicked) { Text(stringResource(R.string.common_try_again)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Hidden while splitting: the parts each carry their own category then, and a
        // whole-transaction pick alongside them would be a second, conflicting answer to
        // the same question.
        if (!uiState.splitEnabled) {
            CategoryDropdown(
                categories = uiState.categories,
                selectedId = uiState.selectedCategoryId,
                onSelected = viewModel::onCategorySelected,
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = uiState.totalText,
            onValueChange = viewModel::onTotalChanged,
            label = { Text(stringResource(R.string.review_total)) },
            isError = uiState.totalError != null,
            supportingText = uiState.totalError?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LineItemsOrSplitSection(
            editableItems = uiState.editableItems,
            splittable = uiState.splittable,
            splitEnabled = uiState.splitEnabled,
            splitRows = uiState.splitRows,
            splitBlockedNote = uiState.splitBlockedNote,
            categories = uiState.categories,
            onItemDescriptionChanged = viewModel::onItemDescriptionChanged,
            onItemAmountChanged = viewModel::onItemAmountChanged,
            onItemAdded = viewModel::onItemAdded,
            onItemRemoved = viewModel::onItemRemoved,
            onSplitToggled = viewModel::onSplitToggled,
            onSplitCategorySelected = viewModel::onSplitCategorySelected,
        )

        // Not an AssistChip: a chip implies tappability, and this message is purely
        // informational -- the retry path is simply tapping Save again.
        //
        // A tinted notice rather than the bare error-red body text this used to be, because it
        // is no longer only the outcome of a save the user just attempted. ReviewViewModel.load
        // restores the row's stored lastError, so on re-opening a receipt whose post was
        // interrupted this appears *before* any button is pressed, as a pre-emptive caution
        // ("check Recent before saving again"). Red text sitting directly under the fields reads
        // as a validation error on the form the user has not filled in yet; a filled notice with
        // its own bounds reads as a statement about the receipt, which is what it is in both
        // cases.
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { showDiscardConfirm = true }) {
                Text(stringResource(R.string.review_discard))
            }
            Button(onClick = viewModel::onSaveClicked, enabled = uiState.canSave) {
                Text(stringResource(R.string.common_save))
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

    if (showDiscardConfirm) {
        // Keyed off the same resolved photoPath the screen renders from, so the dialog can only
        // ever promise to delete something the user can actually see. The stock copy named a
        // photo and extracted details on a Quick Add manual entry -- a row that reaches this
        // screen whenever its post ends ambiguously -- where neither has ever existed. Being
        // wrong about what a destructive action destroys is exactly the moment to be accurate.
        val hasPhoto = uiState.photoPath != null
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = {
                Text(
                    if (hasPhoto) stringResource(R.string.review_discard_receipt_title)
                    else stringResource(R.string.review_discard_transaction_title)
                )
            },
            text = {
                Text(
                    if (hasPhoto) stringResource(R.string.review_discard_receipt_body)
                    else stringResource(R.string.review_discard_transaction_body)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    viewModel.onDiscardClicked()
                }) { Text(stringResource(R.string.review_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showFullImage) {
        uiState.photoPath?.let { path ->
            Dialog(onDismissRequest = { showFullImage = false }) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = path,
                        contentDescription = stringResource(R.string.review_photo_fullsize_desc),
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { showFullImage = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
}

/**
 * The receipt's line items, either as plain text or as a per-item split editor.
 *
 * [splittable] gates whether the "Split by item" [Switch] appears at all -- it never does on a
 * server without splits, or for a receipt whose items and tax don't sum to the total (both
 * unsplittable cases render the old display-only list, with no heading control). When it appears
 * and is off, the list is that same plain text. Turning it on swaps in [splitRows]: each row shows
 * its amount, and every row but the tax one gets its own [CategoryDropdown] keyed by index so a
 * pick lands on the right part regardless of any other row's state; the tax row is never
 * categorised; the parts are what actually post, not [ReviewUiState.selectedCategoryId] (see the
 * guard around this section's call site). [splitBlockedNote] explains an unreconciling receipt on
 * a splits-capable server, in the same tinted-notice style as [ReviewUiState.saveError].
 *
 * When not splitting, each item is editable -- description, amount and a delete -- with an "Add item"
 * button, so a receipt whose per-item costs (or line count) OCR misread can be corrected until it
 * reconciles and the split re-appears. The heading is omitted entirely when there are no items.
 */
@Composable
private fun LineItemsOrSplitSection(
    editableItems: List<EditableItemUi>,
    splittable: Boolean,
    splitEnabled: Boolean,
    splitRows: List<SplitRowUi>,
    splitBlockedNote: String?,
    categories: List<Category>,
    onItemDescriptionChanged: (Int, String) -> Unit,
    onItemAmountChanged: (Int, String) -> Unit,
    onItemAdded: () -> Unit,
    onItemRemoved: (Int) -> Unit,
    onSplitToggled: (Boolean) -> Unit,
    onSplitCategorySelected: (Int, Long?) -> Unit,
) {
    if (editableItems.isEmpty()) return

    Spacer(Modifier.height(20.dp))

    // Header: a quiet section label, with the split toggle when the receipt can be split.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.review_items_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (splittable) {
            // toggleable on the whole row makes the label the switch's accessible name (TalkBack
            // otherwise announces an unlabeled toggle) and gives it a full-width touch target.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = splitEnabled,
                    onValueChange = onSplitToggled,
                    role = Role.Switch,
                ),
            ) {
                Text(
                    stringResource(R.string.review_split_by_item),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = splitEnabled, onCheckedChange = null)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // The items sit in one tonal card so they read as a grouped block, not loose text trailing
    // under the Total field. Rows are separated by hairline dividers.
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (splitEnabled) {
                splitRows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    ItemAmountRow(row.description, row.amount.format())
                    if (!row.categorisable) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.review_not_categorised),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        CategoryDropdown(
                            categories = categories,
                            selectedId = row.categoryId,
                            onSelected = { onSplitCategorySelected(index, it) },
                        )
                    }
                }
            } else {
                editableItems.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    EditableItemRow(
                        item = item,
                        onDescriptionChanged = { onItemDescriptionChanged(index, it) },
                        onAmountChanged = { onItemAmountChanged(index, it) },
                        onRemoved = { onItemRemoved(index) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onItemAdded, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.review_add_item))
                }
            }
        }
    }

    // Informational, not an error, so a neutral tint rather than errorContainer's red -- an
    // unreconciling receipt on a splits-capable server is an expected state, not a failure.
    splitBlockedNote?.let { message ->
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}

/**
 * One receipt line: the description takes the space it can (wrapping to two lines, then ellipsising
 * a very long item name), and the amount is pinned to the right edge so the prices line up down the
 * list instead of trailing each description at a ragged offset.
 */
@Composable
private fun ItemAmountRow(description: String, amount: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * An editable receipt line: description and amount fields plus a delete, for correcting what OCR
 * misread. The amount uses a decimal keyboard and is parsed leniently (like the Total field); a
 * blank or unreadable amount keeps the receipt non-splittable rather than erroring.
 */
@Composable
private fun EditableItemRow(
    item: EditableItemUi,
    onDescriptionChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onRemoved: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = item.description,
            onValueChange = onDescriptionChanged,
            label = { Text(stringResource(R.string.review_item_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = item.amountText,
            onValueChange = onAmountChanged,
            label = { Text(stringResource(R.string.review_item_amount)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.width(120.dp),
        )
        IconButton(onClick = onRemoved) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.review_remove_item))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LineItemsOrSplitSectionPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            LineItemsOrSplitSection(
                editableItems = listOf(
                    EditableItemUi("Bread", "2.50"),
                    EditableItemUi("Milk", "1.20"),
                ),
                splittable = true,
                splitEnabled = true,
                splitRows = listOf(
                    SplitRowUi(Money(BigDecimal("2.50"), "GBP"), "Bread", categorisable = true, categoryId = 1L),
                    SplitRowUi(Money(BigDecimal("1.20"), "GBP"), "Milk", categorisable = true, categoryId = null),
                    SplitRowUi(Money(BigDecimal("0.37"), "GBP"), "Tax", categorisable = false, categoryId = null),
                    SplitRowUi(Money(BigDecimal("-0.50"), "GBP"), "Savings", categorisable = false, categoryId = null),
                ),
                splitBlockedNote = null,
                categories = listOf(Category(1L, "Groceries", null), Category(2L, "Household", null)),
                onItemDescriptionChanged = { _, _ -> },
                onItemAmountChanged = { _, _ -> },
                onItemAdded = {},
                onItemRemoved = {},
                onSplitToggled = {},
                onSplitCategorySelected = { _, _ -> },
            )
        }
    }
}

