package nl.jwdr.ooc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.content.Intent
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
            recordSessions = it.recordSessions,
            setRecordSessions = it::setRecordSessions,
            zipLatestCapture = it.sessionCaptureStore::zipLatest,
        )
    }
    val verboseOpComLogging by viewModel.verboseOpComLogging.collectAsStateWithLifecycle()
    val recordSessions by viewModel.recordSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var noCapture by remember { mutableStateOf(false) }

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
            "Traces raw USB bytes and decoded protocol records to logcat. Takes effect " +
                "immediately. Leave off unless you're chasing an adapter bug.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Record sessions to file", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = recordSessions, onCheckedChange = viewModel::setRecordSessions)
        }
        Text(
            "Writes every connection's decoded frames (.canlog, replayable in tests) and raw " +
                "USB link trace to app storage, flushed line by line. Applies from the next connect. " +
                "Captures may contain your vehicle's identification data — share deliberately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            val zip = viewModel.zipLatestCapture()
            if (zip == null) {
                noCapture = true
            } else {
                noCapture = false
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        }) {
            Text("Share last capture")
        }
        if (noCapture) {
            Text(
                "No capture recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
