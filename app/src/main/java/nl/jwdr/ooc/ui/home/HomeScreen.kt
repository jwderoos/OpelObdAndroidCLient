package nl.jwdr.ooc.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.jwdr.ooc.R
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.navigation.HOME_MENU
import nl.jwdr.ooc.ui.navigation.Route

/**
 * The hub screen: connection status and connect control, the (stub) bus
 * scan, and drill-in entries to the feature screens.
 */
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    onToggleConnection: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionCard(connectionState, onToggleConnection)

        Button(
            onClick = { /* implemented by the ECU scan issue (#11) */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_scan_all))
        }

        for (item in HOME_MENU) {
            Card(
                onClick = { onNavigate(item.route) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connectionState: ConnectionState,
    onToggleConnection: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(connectionState.labelRes()),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onToggleConnection) {
                Text(
                    stringResource(
                        if (connectionState == ConnectionState.Ready) {
                            R.string.action_disconnect
                        } else {
                            R.string.action_connect
                        },
                    ),
                )
            }
        }
    }
}

private fun ConnectionState.labelRes(): Int = when (this) {
    ConnectionState.Disconnected -> R.string.connection_disconnected
    ConnectionState.Connecting -> R.string.connection_connecting
    ConnectionState.Ready -> R.string.connection_ready
    is ConnectionState.Error -> R.string.connection_error
}
