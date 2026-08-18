package nl.jwdr.ooc.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.jwdr.ooc.catalogstore.CatalogSummary
import java.text.DateFormat
import java.util.Date

/**
 * Settings / import screen for M3: onboarding empty state, import actions and
 * the stored catalog's status. Navigation shell arrives with issue #22.
 */
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onImportFolder: () -> Unit,
    onImportFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Diagnostic catalog", style = MaterialTheme.typography.headlineSmall)

        when {
            state.importing -> ImportingIndicator()
            state.summary != null -> CatalogStatusCard(state.summary)
            else -> OnboardingText()
        }

        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onImportFolder, enabled = !state.importing, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.summary == null) "Import catalog folder" else "Re-import catalog folder")
        }
        OutlinedButton(onClick = onImportFile, enabled = !state.importing, modifier = Modifier.fillMaxWidth()) {
            Text("Import a single opeldata.txt")
        }
    }
}

@Composable
private fun ImportingIndicator() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        Text("Validating and importing catalog…")
    }
}

@Composable
private fun CatalogStatusCard(summary: CatalogSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(summary.label, style = MaterialTheme.typography.titleMedium)
            Text("${summary.ecuCount} ECU entries")
            Text("Imported ${DateFormat.getDateTimeInstance().format(Date(summary.importedAtEpochMillis))}")
            Text(
                "Source ${summary.sourceHash.take(12)}…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OnboardingText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No catalog imported yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "This app reads diagnostic definitions (ECUs, fault-code texts, live " +
                "data, output tests, coding tables) from a decoded catalog that you " +
                "import yourself.",
        )
        Text(
            "Decode your own data files on a computer with the OpelObdDataFileDecoder " +
                "project (github.com/jwderoos/OpelObdDataFileDecoder), copy the decoded " +
                "folder to this device, then import it below.",
        )
        Text(
            "Without a catalog, a generic OBD-II fallback mode will still work " +
                "(coming in a later milestone).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
