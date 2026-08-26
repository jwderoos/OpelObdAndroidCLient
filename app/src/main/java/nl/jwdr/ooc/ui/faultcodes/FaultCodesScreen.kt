package nl.jwdr.ooc.ui.faultcodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import nl.jwdr.ooc.ui.ecus.EcuGroupPicker

/**
 * Fault codes (#12, #15): per-ECU stored DTCs with catalog descriptions,
 * and clearing behind an explicit confirmation dialog.
 */
@Composable
fun FaultCodesScreen(
    viewModel: FaultCodesViewModel,
    onOpenEcuList: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        FaultCodesUiState.Loading -> Unit
        FaultCodesUiState.NoVehicle -> NoVehicle(onOpenEcuList, viewModel::useObd2)
        is FaultCodesUiState.PickEcuGroup -> EcuGroupPicker(
            vehicle = current.vehicle,
            groups = current.groups,
            onSelect = viewModel::selectGroup,
        )
        is FaultCodesUiState.PickEcu -> EcuPicker(current.ecus, viewModel::selectEcu)
        is FaultCodesUiState.Faults -> {
            FaultList(
                state = current,
                onRefresh = viewModel::refresh,
                onChangeEcu = viewModel::changeEcu,
                onRequestClear = viewModel::requestClear,
            )
            if (current.confirmingClear) {
                ClearConfirmationDialog(
                    ecuName = current.ecuName,
                    onConfirm = viewModel::confirmClear,
                    onDismiss = viewModel::dismissClear,
                )
            }
        }
    }
}

@Composable
private fun ClearConfirmationDialog(
    ecuName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_dtcs_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ecuName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.clear_dtcs_dialog_message))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.clear_dtcs_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.clear_dtcs_dialog_cancel))
            }
        },
    )
}

@Composable
private fun NoVehicle(onOpenEcuList: () -> Unit, onUseObd2: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.fault_codes_no_vehicle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenEcuList) {
            Text(stringResource(R.string.action_open_ecu_list))
        }
        TextButton(onClick = onUseObd2) {
            Text(stringResource(R.string.action_use_obd2))
        }
    }
}

@Composable
private fun EcuPicker(
    ecus: List<EcuChoice>,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.fault_codes_pick_ecu),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(ecus) { ecu ->
            Card(
                onClick = { onSelect(ecu.name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(ecu.name, style = MaterialTheme.typography.titleMedium)
                    Text(ecu.systemName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FaultList(
    state: FaultCodesUiState.Faults,
    onRefresh: () -> Unit,
    onChangeEcu: () -> Unit,
    onRequestClear: () -> Unit,
) {
    val busy = state.reading || state.clearing
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.ecuName, style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = onChangeEcu, enabled = !busy) {
                    Text(stringResource(R.string.action_change_ecu))
                }
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(stringResource(R.string.action_refresh))
                }
                TextButton(
                    onClick = onRequestClear,
                    enabled = !busy && state.entries.isNotEmpty(),
                ) {
                    Text(
                        text = stringResource(R.string.action_clear_dtcs),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (busy) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(
                        if (state.clearing) R.string.fault_codes_clearing
                        else R.string.fault_codes_reading,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
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
                text = stringResource(R.string.fault_codes_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.entries) { entry -> FaultEntryCard(entry) }
        }
    }
}

@Composable
private fun FaultEntryCard(entry: FaultEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.fault_code_display, entry.code, entry.symptom),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = entry.text ?: stringResource(R.string.fault_codes_no_text),
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.text == null) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
