package nl.jwdr.ooc

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.BluetoothSppLink
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.diagnostics.UsbSerialOpComLink
import nl.jwdr.ooc.service.ConnectionHolderService
import nl.jwdr.ooc.service.shouldRunConnectionHolder
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.transport.opcom.OpComTransport
import nl.jwdr.ooc.ui.livedata.FileLiveDataCsvStore
import nl.jwdr.ooc.ui.livedata.LiveDataCsvStore

/**
 * Composition root: the app-wide singletons, wired by hand. ViewModels reach
 * this through [containerViewModel][nl.jwdr.ooc.ui.containerViewModel].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen; hosts transport background work. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository(CatalogDatabase.get(context).catalogDao())
    }

    private val transportPrefs by lazy {
        appContext.getSharedPreferences("transport", Context.MODE_PRIVATE)
    }

    private val _transportSelection by lazy {
        MutableStateFlow(TransportSelection.decode(transportPrefs.getString(PREF_SELECTION, null)))
    }

    /** The persisted adapter choice, applied to [switchableTransport] at build time. */
    val transportSelection: StateFlow<TransportSelection> by lazy { _transportSelection }

    private val debugPrefs by lazy {
        appContext.getSharedPreferences("debug", Context.MODE_PRIVATE)
    }

    private val _verboseOpComLogging by lazy {
        MutableStateFlow(debugPrefs.getBoolean(PREF_VERBOSE_OPCOM_LOGGING, false))
    }

    /**
     * Off by default. Traces the OP-COM USB link's raw bytes and decoded
     * records to logcat — see [UsbSerialOpComLink] and [OpComTransport].
     * Read live by the transport on every log call, so toggling it takes
     * effect immediately, even on an already-open connection.
     */
    val verboseOpComLogging: StateFlow<Boolean> by lazy { _verboseOpComLogging }

    fun setVerboseOpComLogging(enabled: Boolean) {
        _verboseOpComLogging.value = enabled
        debugPrefs.edit().putBoolean(PREF_VERBOSE_OPCOM_LOGGING, enabled).apply()
    }

    private val switchableTransport by lazy {
        // A persisted ELM selection must never brick startup (Bluetooth
        // removed, MAC corrupted): fall back to the demo transport.
        val initial = runCatching { buildTransport(_transportSelection.value) }.getOrElse {
            _transportSelection.value = TransportSelection.Demo
            buildTransport(TransportSelection.Demo)
        }
        SwitchableObdTransport(initial)
    }

    val diagnosticsManager: DiagnosticsManager by lazy {
        DiagnosticsManager(switchableTransport)
    }

    init {
        applicationScope.launch {
            combine(
                diagnosticsManager.connectionState,
                diagnosticsManager.isSimulated,
                ::shouldRunConnectionHolder,
            ).distinctUntilChanged().collect(::applyConnectionHolderState)
        }
    }

    private fun applyConnectionHolderState(shouldRun: Boolean) {
        val intent = Intent(appContext, ConnectionHolderService::class.java)
        if (shouldRun) {
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "failed to start ConnectionHolderService", e)
            }
        } else {
            appContext.stopService(intent)
        }
    }

    /**
     * Applies and persists a new adapter choice. Only valid while
     * disconnected; [SwitchableObdTransport.switchTo] enforces that.
     */
    fun selectTransport(selection: TransportSelection) {
        switchableTransport.switchTo(buildTransport(selection))
        _transportSelection.value = selection
        transportPrefs.edit().putString(PREF_SELECTION, selection.encode()).apply()
    }

    private fun buildTransport(selection: TransportSelection): ObdTransport = when (selection) {
        TransportSelection.Demo ->
            FakeEcuTransport(applicationScope).apply {
                scriptDemoScanResponses()
                scriptDemoLiveDataResponses()
                scriptDemoObd2Responses()
            }
        is TransportSelection.Elm327Bluetooth -> {
            // Not IllegalStateException: the settings UI reserves that for
            // "disconnect first" refusals from SwitchableObdTransport.
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IllegalArgumentException("device has no Bluetooth adapter")
            Elm327Transport(BluetoothSppLink(adapter.getRemoteDevice(selection.address)))
        }
        TransportSelection.OpComUsb -> {
            val usbManager = appContext.getSystemService(UsbManager::class.java)
                ?: throw IllegalArgumentException("device has no USB manager")
            val device = usbManager.deviceList.values.firstOrNull {
                it.vendorId == UsbSerialOpComLink.VENDOR_ID && it.productId == UsbSerialOpComLink.PRODUCT_ID
            } ?: throw IllegalArgumentException("no OP-COM USB dongle attached")
            // Read the flag per call, not once here: the transport is built lazily at
            // startup and lives for the whole process, so a captured Boolean would only
            // pick up a toggle after an app restart.
            val verbose = { _verboseOpComLogging.value }
            OpComTransport(
                UsbSerialOpComLink(usbManager, device, verboseLogging = verbose),
                applicationScope,
                log = { msg -> if (verbose()) Log.i("OpComTransport", msg) },
            )
        }
    }

    val liveDataCsvStore: LiveDataCsvStore by lazy {
        FileLiveDataCsvStore(File(context.filesDir, "livedata"))
    }

    private companion object {
        const val PREF_SELECTION = "selection"
        const val PREF_VERBOSE_OPCOM_LOGGING = "verbose_opcom_logging"
        const val LOG_TAG = "AppContainer"
    }
}

/**
 * Demo script so the bus scan is drivable against the fake transport: ECUs
 * on an even request ID answer the readDTCByStatus probe with one stored
 * fault; odd IDs stay silent (absent). The response is offered on both
 * common GMLAN response-ID layouts (request+8 and request+0x400); the
 * session only accepts the one matching the catalog's address map.
 */
private fun FakeEcuTransport.scriptDemoScanResponses() {
    val pad = 0xAA.toByte()
    onMatch { it.data.size >= 2 && it.data[0].toInt() == 0x04 && it.data[1].toInt() == 0x18 }
        .respondBy { request ->
            if (request.id % 2 != 0) return@respondBy emptyList()
            val payload = byteArrayOf(0x05, 0x58, 0x01, 0x01, 0x70, 0xE1.toByte(), pad, pad)
            listOf(CanFrame(request.id + 8, payload), CanFrame(request.id + 0x400, payload))
        }
}

/**
 * Demo script for the live-data screen: readDataByLocalIdentifier requests
 * get a 4-byte record whose first byte drifts, so the chart visibly moves.
 */
private fun FakeEcuTransport.scriptDemoLiveDataResponses() {
    var tick = 0
    onMatch { it.data.size >= 3 && it.data[0].toInt() == 0x02 && it.data[1].toInt() == 0x21 }
        .respondBy { request ->
            if (request.id % 2 != 0) return@respondBy emptyList()
            tick++
            val lid = request.data[2]
            val value = (0x50 + (tick % 32)).toByte()
            val payload =
                byteArrayOf(0x06, 0x61, lid, value, (tick % 2).toByte(), 0x2A, 0x64, 0xAA.toByte())
            listOf(CanFrame(request.id + 8, payload), CanFrame(request.id + 0x400, payload))
        }
}

/**
 * Demo script for the generic OBD-II fallback (#14): one ECU (0x7E0/0x7E8)
 * answers the functional probe, serves a few scaled mode 01 PIDs, and stores
 * one clearable emission DTC.
 */
private fun FakeEcuTransport.scriptDemoObd2Responses() {
    val pad = 0xAA.toByte()
    fun response(vararg values: Int) =
        CanFrame(0x7E8, ByteArray(8) { if (it < values.size) values[it].toByte() else pad })

    // Supported PIDs 0x05, 0x0C, 0x0D, 0x11 (mask 08 18 80 00), served both
    // for the functional discovery probe and the physical range query.
    val supportedMask = response(0x06, 0x41, 0x00, 0x08, 0x18, 0x80, 0x00)
    onMatch { it.id == 0x7DF && it.data[0].toInt() == 0x02 && it.data[1].toInt() == 0x01 }
        .respondBy { listOf(supportedMask) }

    var tick = 0
    onMatch { it.id == 0x7E0 && it.data[0].toInt() == 0x02 && it.data[1].toInt() == 0x01 }
        .respondBy { request ->
            tick++
            when (val pid = request.data[2].toInt() and 0xFF) {
                0x00 -> listOf(supportedMask)
                0x0C -> listOf(response(0x04, 0x41, pid, 0x1D + (tick % 8), 0xF8))
                0x05 -> listOf(response(0x03, 0x41, pid, 0x5A + (tick % 5)))
                0x0D -> listOf(response(0x03, 0x41, pid, 0x30 + (tick % 16)))
                0x11 -> listOf(response(0x03, 0x41, pid, 0x40 + (tick % 32)))
                else -> listOf(response(0x03, 0x7F, 0x01, 0x12))
            }
        }

    var cleared = false
    onMatch { it.id == 0x7E0 && it.data[0].toInt() == 0x01 && it.data[1].toInt() == 0x03 }
        .respondBy {
            if (cleared) {
                listOf(response(0x02, 0x43, 0x00))
            } else {
                listOf(response(0x04, 0x43, 0x01, 0x01, 0x43))
            }
        }
    onMatch { it.id == 0x7E0 && it.data[0].toInt() == 0x01 && it.data[1].toInt() == 0x04 }
        .respondBy {
            cleared = true
            listOf(response(0x01, 0x44))
        }
}

class OocApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ConnectionHolderService.createNotificationChannel(this)
    }
}
