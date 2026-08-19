package nl.jwdr.ooc.ui.outputtests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import nl.jwdr.ooc.catalog.OutputTestType
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.faultcodes.EcuChoice

/**
 * Output tests (#16): catalog actuator tests behind an explicit confirmation
 * dialog showing the test's preconditions (design spec safety rule).
 */
@Composable
fun OutputTestsScreen(
    viewModel: OutputTestsViewModel,
    onOpenEcuList: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        OutputTestsUiState.Loading -> Unit
        OutputTestsUiState.NoVehicle -> NoVehicle(onOpenEcuList)
        is OutputTestsUiState.PickEcu -> EcuPicker(current.ecus, viewModel::selectEcu)
        is OutputTestsUiState.Tests -> {
            TestList(
                state = current,
                onRequestStart = viewModel::requestStart,
                onChangeEcu = viewModel::changeEcu,
            )
            current.confirming?.let { index ->
                current.tests.getOrNull(index)?.let { test ->
                    StartConfirmationDialog(
                        test = test,
                        onConfirm = viewModel::confirmStart,
                        onDismiss = viewModel::dismissStart,
                    )
                }
            }
        }
        is OutputTestsUiState.Running -> RunPanel(
            state = current,
            onActivate = viewModel::activate,
            onDeactivate = viewModel::deactivate,
            onStop = viewModel::stop,
        )
    }
}

@Composable
private fun StartConfirmationDialog(
    test: OutputTestChoice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.output_test_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(test.title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.output_test_dialog_message))
                if (test.preTestInstructions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.output_test_dialog_no_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    test.preTestInstructions.forEach { instruction ->
                        Text(
                            text = "• $instruction",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.output_test_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.output_test_dialog_cancel))
            }
        },
    )
}

@Composable
private fun NoVehicle(onOpenEcuList: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.output_tests_no_vehicle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenEcuList) {
            Text(stringResource(R.string.action_open_ecu_list))
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
                text = stringResource(R.string.output_tests_pick_ecu),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        itemsIndexed(ecus) { _, ecu ->
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
private fun TestList(
    state: OutputTestsUiState.Tests,
    onRequestStart: (Int) -> Unit,
    onChangeEcu: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.ecuName, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onChangeEcu, enabled = !state.starting) {
                Text(stringResource(R.string.action_change_ecu))
            }
        }

        if (state.starting) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.output_tests_starting),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }

        state.error?.let { error -> ErrorText(error) }

        if (!state.starting && state.tests.isEmpty()) {
            Text(
                text = stringResource(R.string.output_tests_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.tests) { index, test ->
                Card(
                    onClick = { if (!state.starting) onRequestStart(index) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(test.title, style = MaterialTheme.typography.titleMedium)
                        Text(test.type.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunPanel(
    state: OutputTestsUiState.Running,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(state.ecuName, style = MaterialTheme.typography.titleMedium)
        Text(state.test.title, style = MaterialTheme.typography.titleLarge)

        state.test.activeLabels.forEach { label ->
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }

        if (state.readouts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.readouts.forEach { readout ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = readout.binding.row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = readout.display,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        if (state.test.type == OutputTestType.ONOFF) {
            Text(
                text = stringResource(
                    if (state.active) R.string.output_test_active
                    else R.string.output_test_inactive,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (state.active) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.test.type) {
                OutputTestType.ONOFF -> {
                    Button(onClick = onActivate, enabled = !state.busy && !state.active) {
                        Text(stringResource(R.string.output_test_activate))
                    }
                    OutlinedButton(onClick = onDeactivate, enabled = !state.busy && state.active) {
                        Text(stringResource(R.string.output_test_deactivate))
                    }
                }
                OutputTestType.UPDOWN -> {
                    Button(onClick = onActivate, enabled = !state.busy) {
                        Text(stringResource(R.string.output_test_up))
                    }
                    OutlinedButton(onClick = onDeactivate, enabled = !state.busy) {
                        Text(stringResource(R.string.output_test_down))
                    }
                }
                OutputTestType.REPEAT -> {
                    Button(onClick = onActivate, enabled = !state.busy) {
                        Text(stringResource(R.string.output_test_trigger))
                    }
                }
            }
        }

        if (state.busy) {
            Text(
                text = stringResource(R.string.output_tests_busy),
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let { error -> ErrorText(error) }

        state.test.postTestInstructions.forEach { instruction ->
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Button(
            onClick = onStop,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_stop_test))
        }
    }
}

@Composable
private fun ErrorText(error: UserMessage) {
    Text(
        text = stringResource(error.resId, *error.formatArgs.toTypedArray()),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
