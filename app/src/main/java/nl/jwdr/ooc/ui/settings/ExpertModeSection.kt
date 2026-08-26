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

/** Expert mode: gates the Coding screen (issue #18) behind an explicit opt-in. */
@Composable
fun ExpertModeSection(modifier: Modifier = Modifier) {
    val viewModel = containerViewModel {
        ExpertModeViewModel(expertMode = it.expertMode, setExpertMode = it::setExpertMode)
    }
    val expertMode by viewModel.expertMode.collectAsStateWithLifecycle()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Expert mode", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Enable ECU coding", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = expertMode, onCheckedChange = viewModel::setExpertMode)
        }
        Text(
            "Shows the Coding screen, which reads and writes raw control-unit coding data. " +
                "An incorrect value can disable a feature or make a module malfunction. Off by default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
