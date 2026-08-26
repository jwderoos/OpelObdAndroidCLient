package nl.jwdr.ooc.ui.coding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.R
import nl.jwdr.ooc.diagnostics.CodingEntryOutcome
import nl.jwdr.ooc.ui.faultcodes.EcuChoice

/**
 * ECU coding read/write (#18): raw hex per DID-entry, behind the expert-mode
 * toggle and an explicit confirmation dialog before any write.
 */
@Composable
fun CodingScreen(
    viewModel: CodingViewModel,
    onOpenEcuList: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        CodingUiState.Loading -> Unit
        CodingUiState.NoVehicle -> NoVehicle(onOpenEcuList)
        is CodingUiState.PickEcu -> EcuPicker(current.ecus, viewModel::selectEcu)
        is CodingUiState.PickTable -> TablePicker(current, viewModel::selectTable)
        is CodingUiState.Entries -> {
            EntryList(
                state = current,
                onEdit = viewModel::editEntry,
                onChangeEcu = viewModel::changeEcu,
                onChangeTable = viewModel::changeTable,
                onRequestWrite = viewModel::requestWrite,
            )
            if (current.confirmingWrite) {
                WriteConfirmationDialog(
                    ecuName = current.ecuName,
                    changes = current.entries.filter { it.isChanged },
                    onConfirm = viewModel::confirmWrite,
                    onDismiss = viewModel::dismissWrite,
                )
            }
        }
    }
}

@Composable
private fun NoVehicle(onOpenEcuList: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.coding_no_vehicle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenEcuList) {
            Text(stringResource(R.string.action_open_ecu_list))
        }
    }
}

@Composable
private fun EcuPicker(ecus: List<EcuChoice>, onSelect: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.coding_pick_ecu),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(ecus) { ecu ->
            Card(onClick = { onSelect(ecu.name) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(ecu.name, style = MaterialTheme.typography.titleMedium)
                    Text(ecu.systemName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TablePicker(state: CodingUiState.PickTable, onSelect: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.coding_pick_table),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(state.tables) { table ->
            Card(onClick = { onSelect(table.dataIdentifier) }, modifier = Modifier.fillMaxWidth()) {
                Text(table.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun EntryList(
    state: CodingUiState.Entries,
    onEdit: (Int, String) -> Unit,
    onChangeEcu: () -> Unit,
    onChangeTable: () -> Unit,
    onRequestWrite: () -> Unit,
) {
    val busy = state.loading || state.writing
    val changes = state.entries.filter { it.isChanged }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.ecuName, style = MaterialTheme.typography.titleMedium)
                Text(state.tableLabel, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = onChangeTable, enabled = !busy) {
                    Text(stringResource(R.string.action_change_table))
                }
                TextButton(onClick = onChangeEcu, enabled = !busy) {
                    Text(stringResource(R.string.action_change_ecu))
                }
            }
        }

        if (busy) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(if (state.writing) R.string.coding_writing else R.string.coding_reading),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        state.error?.let { error ->
            Text(
                text = stringResource(error.resId, *error.formatArgs.toTypedArray()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (!busy && state.error == null && state.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.coding_entries_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.entries) { entry -> EntryCard(entry, enabled = !busy, onEdit = { onEdit(entry.id, it) }) }
            if (changes.isNotEmpty()) {
                // Spec: the old -> new hex of every edited row is reviewable
                // before the confirmation dialog is reachable.
                item { ReviewChanges(changes) }
            }
            item {
                TextButton(onClick = onRequestWrite, enabled = !busy && changes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.action_write_coding),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** A row the user has actually changed (an edit equal to the current value is not a change). */
private val CodingEntryDisplay.isChanged: Boolean
    get() = editedHex != null && editedHex != currentHex

private fun CodingEntryDisplay.idLabel(): String = "0x%02X".format(id)

@Composable
private fun EntryCard(entry: CodingEntryDisplay, enabled: Boolean, onEdit: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("%s (%d bytes)".format(entry.idLabel(), entry.count), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = entry.editedHex ?: entry.currentHex,
                onValueChange = onEdit,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (entry.isChanged) {
                // Typing replaces the read-back value in the field; keep the
                // original visible (and restorable) instead of losing it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.coding_entry_was, entry.currentHex),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Re-typing the read-back value is what "no pending change"
                    // means to the ViewModel's edited-entries filter.
                    TextButton(onClick = { onEdit(entry.currentHex) }, enabled = enabled) {
                        Text(stringResource(R.string.action_revert))
                    }
                }
            }
            entry.outcome?.let { outcome -> Text(outcomeText(outcome), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The spec's "Review changes" panel: old -> new hex for every edited row. */
@Composable
private fun ReviewChanges(changes: List<CodingEntryDisplay>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.coding_review_changes), style = MaterialTheme.typography.titleSmall)
            changes.forEach { entry -> Text(changeLine(entry), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun changeLine(entry: CodingEntryDisplay): String =
    stringResource(R.string.coding_change_line, entry.idLabel(), entry.currentHex, entry.editedHex.orEmpty())

@Composable
private fun outcomeText(outcome: CodingEntryOutcome): String = when (outcome) {
    is CodingEntryOutcome.Written -> stringResource(R.string.coding_outcome_written)
    is CodingEntryOutcome.NotAttempted -> stringResource(R.string.coding_outcome_not_attempted)
    is CodingEntryOutcome.Failed -> stringResource(R.string.coding_outcome_failed, outcome.reason)
    is CodingEntryOutcome.VerificationMismatch -> stringResource(
        R.string.coding_outcome_mismatch,
        outcome.expected.joinToString("") { "%02X".format(it) },
        outcome.actual.joinToString("") { "%02X".format(it) },
    )
}

@Composable
private fun WriteConfirmationDialog(
    ecuName: String,
    changes: List<CodingEntryDisplay>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coding_write_dialog_title)) },
        text = {
            // Scrolls: a coding table can have more changed ids than fit an
            // AlertDialog, and the id list is the consequence being confirmed.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(ecuName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.coding_write_dialog_message, changes.size))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    changes.forEach { entry ->
                        Text(changeLine(entry), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.coding_write_dialog_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.coding_write_dialog_cancel)) }
        },
    )
}
