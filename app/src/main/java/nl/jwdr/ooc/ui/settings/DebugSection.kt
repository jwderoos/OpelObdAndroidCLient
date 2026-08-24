package nl.jwdr.ooc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.ui.containerViewModel

/**
 * Debug settings: toggles for diagnostic logging that a development session
 * added to chase a specific bug and left in place (gated, off by default)
 * instead of deleting, so a later session can re-enable capture on real
 * hardware without recompiling. Not meant to be discovered by end users.
 */
@Composable
fun DebugSection(modifier: Modifier = Modifier) {
    val viewModel = containerViewModel {
        DebugViewModel(
            verboseOpComLogging = it.verboseOpComLogging,
            setVerboseOpComLogging = it::setVerboseOpComLogging,
        )
    }
    val verboseOpComLogging by viewModel.verboseOpComLogging.collectAsStateWithLifecycle()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Debug", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Verbose OP-COM USB logging", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = verboseOpComLogging, onCheckedChange = viewModel::setVerboseOpComLogging)
        }
        Text(
            "Traces raw USB bytes and decoded protocol records to logcat. Takes effect on the " +
                "next OP-COM connection, not the current one. Leave off unless you're chasing an " +
                "adapter bug.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
