package nl.jwdr.ooc.ui.ecus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.R
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.ui.UserMessage

/**
 * ECU list (#11): vehicle picker when nothing is selected, then the selected
 * vehicle's ECUs with per-ECU presence and fault status from the bus scan.
 */
@Composable
fun EcuListScreen(
    viewModel: EcuListViewModel,
    autoScan: Boolean,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(autoScan, state is EcuListUiState.Ecus) {
        if (autoScan && state is EcuListUiState.Ecus) viewModel.startScan()
    }

    when (val current = state) {
        EcuListUiState.Loading -> Unit
        EcuListUiState.NoCatalog -> NoCatalog(onOpenSettings)
        is EcuListUiState.PickVehicle -> VehiclePicker(current.vehicles, viewModel::selectVehicle)
        is EcuListUiState.Ecus -> EcuList(
            state = current,
            onScan = viewModel::startScan,
            onChangeVehicle = viewModel::changeVehicle,
        )
    }
}

@Composable
private fun NoCatalog(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.ecu_list_no_catalog),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}

@Composable
private fun VehiclePicker(
    vehicles: List<VehicleRef>,
    onSelect: (VehicleRef) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.ecu_list_pick_vehicle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(vehicles) { vehicle ->
            Card(
                onClick = { onSelect(vehicle) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(vehicle.vehicle, style = MaterialTheme.typography.titleMedium)
                    Text(vehicle.modelYear, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun EcuList(
    state: EcuListUiState.Ecus,
    onScan: () -> Unit,
    onChangeVehicle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.vehicle.vehicle, style = MaterialTheme.typography.titleMedium)
                Text(state.vehicle.modelYear, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onChangeVehicle, enabled = !state.scanning) {
                Text(stringResource(R.string.action_change_vehicle))
            }
        }

        Button(
            onClick = onScan,
            enabled = !state.scanning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(if (state.scanning) R.string.ecu_status_scanning else R.string.action_scan))
        }

        if (state.scanning) {
            val done = state.rows.count { it.status !is EcuRowStatus.NotScanned && it.status != EcuRowStatus.Scanning }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.ecu_scan_progress, done, state.rows.size),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = { if (state.rows.isEmpty()) 0f else done.toFloat() / state.rows.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }

        state.error?.let { error ->
            Text(
                text = error.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows) { row -> EcuRowCard(row) }
        }
    }
}

@Composable
private fun EcuRowCard(row: EcuRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(row.systemName, style = MaterialTheme.typography.bodySmall)
            }
            Box { StatusLabel(row.status) }
        }
    }
}

@Composable
private fun StatusLabel(status: EcuRowStatus) {
    val (text, color) = when (status) {
        EcuRowStatus.NotScanned ->
            stringResource(R.string.ecu_status_not_scanned) to MaterialTheme.colorScheme.outline
        EcuRowStatus.Scanning ->
            stringResource(R.string.ecu_status_scanning) to MaterialTheme.colorScheme.primary
        is EcuRowStatus.Present -> when (status.dtcCount) {
            null -> stringResource(R.string.ecu_status_present_unknown) to MaterialTheme.colorScheme.primary
            0 -> stringResource(R.string.ecu_status_present_no_faults) to MaterialTheme.colorScheme.primary
            else -> pluralStringResource(
                R.plurals.ecu_status_fault_count,
                status.dtcCount,
                status.dtcCount,
            ) to MaterialTheme.colorScheme.error
        }
        EcuRowStatus.Absent ->
            stringResource(R.string.ecu_status_absent) to MaterialTheme.colorScheme.outline
    }
    Text(text = text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun UserMessage.resolve(): String =
    stringResource(resId, *formatArgs.toTypedArray())
