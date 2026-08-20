package nl.jwdr.ooc.ui.settings

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.diagnostics.UsbSerialOpComLink
import nl.jwdr.ooc.ui.containerViewModel

/**
 * Adapter selection: the built-in demo ECU or an ELM327 over Bluetooth,
 * picked from the system's already-paired devices behind the
 * BLUETOOTH_CONNECT runtime permission. Switching is disabled while a
 * session is connecting or connected.
 */
@Composable
fun AdapterSection(modifier: Modifier = Modifier) {
    val viewModel = containerViewModel {
        TransportViewModel(
            selection = it.transportSelection,
            connectionState = it.diagnosticsManager.connectionState,
            applySelection = it::selectTransport,
        )
    }
    val context = LocalContext.current
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val canSwitch by viewModel.canSwitch.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var showDevicePicker by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var opComMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) showDevicePicker = true
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Adapter", style = MaterialTheme.typography.headlineSmall)

        AdapterChoiceRow(
            label = "Demo (simulated ECU)",
            selected = selection is TransportSelection.Demo,
            enabled = canSwitch,
            onClick = { viewModel.select(TransportSelection.Demo) },
        )
        val elm = selection as? TransportSelection.Elm327Bluetooth
        AdapterChoiceRow(
            label = elm?.let { "ELM327 — ${it.name.ifEmpty { it.address }}" } ?: "ELM327 (Bluetooth)",
            selected = elm != null,
            enabled = canSwitch,
            onClick = {
                if (hasBluetoothConnectPermission(context)) {
                    showDevicePicker = true
                } else {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
        )
        AdapterChoiceRow(
            label = "OP-COM (USB)",
            selected = selection is TransportSelection.OpComUsb,
            enabled = canSwitch,
            onClick = {
                val usbManager = context.getSystemService(UsbManager::class.java)
                val device = usbManager?.deviceList?.values?.firstOrNull {
                    it.vendorId == UsbSerialOpComLink.VENDOR_ID && it.productId == UsbSerialOpComLink.PRODUCT_ID
                }
                when {
                    usbManager == null || device == null ->
                        opComMessage = "No OP-COM USB dongle detected. Plug it in via a USB-OTG adapter."
                    usbManager.hasPermission(device) -> {
                        opComMessage = null
                        viewModel.select(TransportSelection.OpComUsb)
                    }
                    else -> requestUsbPermission(context, usbManager, device) { granted ->
                        if (granted) {
                            opComMessage = null
                            viewModel.select(TransportSelection.OpComUsb)
                        } else {
                            opComMessage = "USB permission was denied for the OP-COM dongle."
                        }
                    }
                }
            },
        )

        if (!canSwitch) {
            Text(
                "Disconnect to change the adapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (permissionDenied) {
            Text(
                "Bluetooth permission was denied. Allow \"Nearby devices\" for this app in Android settings to use an ELM327 adapter.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        opComMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showDevicePicker) {
        PairedDevicePicker(
            onPick = { address, name ->
                viewModel.select(TransportSelection.Elm327Bluetooth(address, name))
                showDevicePicker = false
            },
            onDismiss = { showDevicePicker = false },
        )
    }
}

@Composable
private fun AdapterChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PairedDevicePicker(
    onPick: (address: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val devices = remember { pairedDevices(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Paired Bluetooth devices") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices. Pair the ELM327 adapter in Android's Bluetooth settings first (PIN is usually 1234).")
            } else {
                Column {
                    devices.forEach { (address, name) ->
                        Text(
                            text = if (name.isEmpty()) address else "$name\n$address",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = false, onClick = { onPick(address, name) })
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    )
}

private fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Requests the system's USB device-access dialog for [device] and invokes
 * [onResult] once the user answers. Unlike the Bluetooth runtime permission,
 * USB access is per-device and has no [rememberLauncherForActivityResult]
 * contract — it's a broadcast-based API.
 */
private fun requestUsbPermission(
    context: Context,
    usbManager: UsbManager,
    device: UsbDevice,
    onResult: (granted: Boolean) -> Unit,
) {
    val action = "${context.packageName}.USB_PERMISSION"
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            context.unregisterReceiver(this)
            onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(action), flags)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, IntentFilter(action))
    }
    usbManager.requestPermission(device, pendingIntent)
}

/** Paired devices as (address, name); empty when Bluetooth is off or blocked. */
private fun pairedDevices(context: Context): List<Pair<String, String>> = try {
    context.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.bondedDevices
        .orEmpty()
        .map { it.address to (it.name ?: "") }
        .sortedBy { it.second }
} catch (e: SecurityException) {
    emptyList()
}
