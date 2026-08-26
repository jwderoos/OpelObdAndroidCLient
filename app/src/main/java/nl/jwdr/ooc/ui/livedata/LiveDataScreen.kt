package nl.jwdr.ooc.ui.livedata

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import nl.jwdr.ooc.R
import nl.jwdr.ooc.ui.faultcodes.EcuChoice

/**
 * Live data (#13): poll one measuring block, show decoded rows (raw values
 * plus catalog state labels; the catalog defines no scaling), chart a tapped
 * numeric row, and record readings to a shareable CSV.
 */
@Composable
fun LiveDataScreen(
    viewModel: LiveDataViewModel,
    onOpenEcuList: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Back steps one level within this screen (Live -> block picker -> ECU
    // picker) instead of leaving live data entirely; from the ECU picker it
    // falls through to the caller's navigation (previous menu).
    BackHandler(enabled = state is LiveDataUiState.Live || state is LiveDataUiState.PickBlock) {
        when (state) {
            is LiveDataUiState.Live -> viewModel.changeBlock()
            is LiveDataUiState.PickBlock -> viewModel.changeEcu()
            else -> Unit
        }
    }

    when (val current = state) {
        LiveDataUiState.Loading -> Unit
        LiveDataUiState.NoVehicle -> NoVehicle(onOpenEcuList, viewModel::useObd2)
        is LiveDataUiState.PickEcu -> EcuPicker(current.ecus, viewModel::selectEcu)
        is LiveDataUiState.PickBlock -> BlockPicker(
            state = current,
            onSelect = viewModel::selectBlock,
            onChangeEcu = viewModel::changeEcu,
        )
        is LiveDataUiState.Live -> LiveBlock(
            state = current,
            onChangeBlock = viewModel::changeBlock,
            onStartLogging = viewModel::startLogging,
            onStopLogging = viewModel::stopLogging,
        )
    }
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
                text = stringResource(R.string.live_data_pick_ecu),
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
private fun BlockPicker(
    state: LiveDataUiState.PickBlock,
    onSelect: (Int) -> Unit,
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
            TextButton(onClick = onChangeEcu) {
                Text(stringResource(R.string.action_change_ecu))
            }
        }
        if (state.blocks.isEmpty()) {
            Text(
                text = stringResource(R.string.live_data_no_blocks),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.live_data_pick_block),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(state.blocks) { block ->
                    Card(
                        onClick = { onSelect(block.number) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(block.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBlock(
    state: LiveDataUiState.Live,
    onChangeBlock: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
) {
    var expandedRow by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.ecuName, style = MaterialTheme.typography.titleMedium)
                Text(state.blockTitle, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = onChangeBlock) {
                    Text(stringResource(R.string.action_change_block))
                }
                if (state.logging) {
                    TextButton(onClick = onStopLogging) {
                        Text(
                            text = stringResource(R.string.action_stop_logging),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    TextButton(onClick = onStartLogging, enabled = state.polling) {
                        Text(stringResource(R.string.action_start_logging))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.live_data_raw_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (state.logging) {
            Text(
                text = stringResource(R.string.live_data_logging),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        state.savedCsvPath?.let { path -> SavedCsvRow(path) }

        state.error?.let { error ->
            Text(
                text = stringResource(error.resId, *error.formatArgs.toTypedArray()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (state.polling && state.rows.isEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.live_data_waiting),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows.withIndex().toList(), key = { it.index }) { (index, row) ->
                LiveRowCard(
                    row = row,
                    expanded = expandedRow == index,
                    onToggle = {
                        expandedRow = if (expandedRow == index) null else index
                    },
                )
            }
        }
    }
}

@Composable
private fun SavedCsvRow(path: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.live_data_csv_saved, File(path).name),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path),
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null))
        }) {
            Text(stringResource(R.string.action_share_csv))
        }
    }
}

@Composable
private fun LiveRowCard(
    row: LiveRow,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = listOfNotNull(row.display, row.unit).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (expanded && row.isNumeric && row.samples.size >= 2) {
                SampleChart(
                    samples = row.samples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

/** Minimal polyline chart of the row's recent raw values. */
@Composable
private fun SampleChart(samples: List<Sample>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val minT = samples.first().timestampMs
        val maxT = samples.last().timestampMs
        val spanT = (maxT - minT).coerceAtLeast(1)
        val minV = samples.minOf { it.value }
        val maxV = samples.maxOf { it.value }
        val spanV = (maxV - minV).coerceAtLeast(1.0)

        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height))
        drawLine(gridColor, Offset.Zero, Offset(0f, size.height))

        val path = Path()
        samples.forEachIndexed { i, sample ->
            val x = (sample.timestampMs - minT).toFloat() / spanT * size.width
            val y = size.height - ((sample.value - minV) / spanV * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
    }
}
