package nl.jwdr.ooc.ui.faultcodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.jwdr.ooc.catalog.DtcCode
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.EcuScanTarget
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.userMessageFor

/** One selectable ECU on the fault-code screen's picker. */
data class EcuChoice(
    val name: String,
    val systemName: String,
)

/** One displayed fault: `P0016 - 00` plus the catalog text, if listed. */
data class FaultEntry(
    val code: String,
    val symptom: Int,
    /** Catalog description; null when the catalog does not list the fault. */
    val text: String?,
)

/** What the fault-code screen shows. */
sealed interface FaultCodesUiState {
    /** Upstream flows have not emitted yet. */
    data object Loading : FaultCodesUiState

    /** No catalog or no vehicle selected; point the user to the ECU list. */
    data object NoVehicle : FaultCodesUiState

    /** The selected vehicle's diagnosable ECUs, to pick one to read. */
    data class PickEcu(val ecus: List<EcuChoice>) : FaultCodesUiState

    /** The stored faults of one ECU. */
    data class Faults(
        val ecuName: String,
        val entries: List<FaultEntry>,
        val reading: Boolean,
        val error: UserMessage?,
        /** The destructive-clear confirmation dialog is showing. */
        val confirmingClear: Boolean = false,
        /** A confirmed clear is on the bus. */
        val clearing: Boolean = false,
    ) : FaultCodesUiState
}

class FaultCodesViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
    /** ECU to read on arrival (navigation from the ECU list), if any. */
    initialEcuName: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<FaultCodesUiState>(FaultCodesUiState.Loading)
    val state: StateFlow<FaultCodesUiState> = _state

    /** The selected vehicle's CAN ECU definitions, keyed off the picker. */
    private var definitions: List<EcuDefinition> = emptyList()

    /** Non-null while in the no-catalog OBD-II fallback mode. */
    private var obd2Targets: List<EcuScanTarget>? = null
    private var pendingEcuName: String? = initialEcuName
    private var readJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.selectedVehicle,
                ::Pair,
            ).collectLatest { (summary, selected) ->
                readJob?.cancel()
                obd2Targets = null
                if (summary == null || selected == null) {
                    _state.value = FaultCodesUiState.NoVehicle
                    return@collectLatest
                }
                definitions = repository.canEcusFor(selected)
                val pending = pendingEcuName?.also { pendingEcuName = null }
                val target = pending?.let { name -> definitions.find { it.name == name } }
                if (target != null) {
                    read(target)
                } else {
                    _state.value = pickerState()
                }
            }
        }
    }

    /**
     * Enters the generic OBD-II fallback (#14): discovers the emission ECUs
     * on the bus and offers them, without any imported catalog.
     */
    fun useObd2() {
        if (_state.value !is FaultCodesUiState.NoVehicle) return
        readJob?.cancel()
        viewModelScope.launch {
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val targets = diagnosticsManager.discoverObd2Ecus()
                obd2Targets = targets
                _state.value = FaultCodesUiState.PickEcu(
                    targets.map { EcuChoice(it.name, OBD2_SYSTEM_NAME) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = FaultCodesUiState.NoVehicle
            }
        }
    }

    fun selectEcu(name: String) {
        obd2Targets?.let { targets ->
            targets.find { it.name == name }?.let { readObd2(it) }
            return
        }
        val definition = definitions.find { it.name == name } ?: return
        read(definition)
    }

    fun refresh() {
        val current = _state.value
        if (current !is FaultCodesUiState.Faults || current.reading || current.clearing) return
        obd2Targets?.let { targets ->
            targets.find { it.name == current.ecuName }?.let { readObd2(it) }
            return
        }
        definitions.find { it.name == current.ecuName }?.let { read(it) }
    }

    fun requestClear() {
        _state.update {
            if (it is FaultCodesUiState.Faults &&
                !it.reading && !it.clearing && it.entries.isNotEmpty()
            ) {
                it.copy(confirmingClear = true)
            } else {
                it
            }
        }
    }

    fun dismissClear() {
        _state.update {
            if (it is FaultCodesUiState.Faults) it.copy(confirmingClear = false) else it
        }
    }

    fun confirmClear() {
        val current = _state.value
        if (current !is FaultCodesUiState.Faults || !current.confirmingClear) return
        val obd2Target = obd2Targets?.find { it.name == current.ecuName }
        if (obd2Target != null) {
            clearWith(current) { diagnosticsManager.obd2ClearDtcs(obd2Target).map(::obd2Entry) }
            return
        }
        val definition = definitions.find { it.name == current.ecuName } ?: return
        val address = definition.address as? EcuAddress.Can ?: return
        clearWith(current) {
            val remaining = diagnosticsManager.clearDtcs(
                EcuScanTarget(definition.name, address.requestId, address.responseId, bus = address.bus),
            )
            faultEntries(definition, remaining)
        }
    }

    private fun clearWith(
        current: FaultCodesUiState.Faults,
        clear: suspend () -> List<FaultEntry>,
    ) {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _state.value = current.copy(confirmingClear = false, clearing = true, error = null)
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                _state.value = FaultCodesUiState.Faults(
                    ecuName = current.ecuName,
                    entries = clear(),
                    reading = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it is FaultCodesUiState.Faults) {
                        it.copy(clearing = false, error = userMessageFor(e))
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun changeEcu() {
        readJob?.cancel()
        _state.value = obd2Targets?.let { targets ->
            FaultCodesUiState.PickEcu(targets.map { EcuChoice(it.name, OBD2_SYSTEM_NAME) })
        } ?: pickerState()
    }

    private fun pickerState() =
        FaultCodesUiState.PickEcu(definitions.map { EcuChoice(it.name, it.systemName) })

    private fun obd2Entry(code: Int) = FaultEntry(DtcCode.format(code), symptom = 0, text = null)

    private fun readObd2(target: EcuScanTarget) {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _state.value = FaultCodesUiState.Faults(
                ecuName = target.name,
                entries = emptyList(),
                reading = true,
                error = null,
            )
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                _state.value = FaultCodesUiState.Faults(
                    ecuName = target.name,
                    entries = diagnosticsManager.obd2ReadDtcs(target).map(::obd2Entry),
                    reading = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it is FaultCodesUiState.Faults) {
                        it.copy(reading = false, error = userMessageFor(e))
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun read(definition: EcuDefinition) {
        val address = definition.address as? EcuAddress.Can ?: return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _state.value = FaultCodesUiState.Faults(
                ecuName = definition.name,
                entries = emptyList(),
                reading = true,
                error = null,
            )
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val target = EcuScanTarget(definition.name, address.requestId, address.responseId, bus = address.bus)
                val dtcs = diagnosticsManager.readDtcs(target)
                _state.value = FaultCodesUiState.Faults(
                    ecuName = definition.name,
                    entries = faultEntries(definition, dtcs),
                    reading = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it is FaultCodesUiState.Faults) {
                        it.copy(reading = false, error = userMessageFor(e))
                    } else {
                        it
                    }
                }
            }
        }
    }

    private suspend fun faultEntries(
        definition: EcuDefinition,
        dtcs: List<Dtc>,
    ): List<FaultEntry> {
        val catalog = definition.catalogKey?.let { repository.faultCodesFor(it) }
        return dtcs.map { dtc ->
            val code = DtcCode.format(dtc.code)
            FaultEntry(code, dtc.symptom, catalog?.textFor(code, dtc.symptom))
        }
    }

    private companion object {
        /** Picker subtitle of the fallback mode. */
        const val OBD2_SYSTEM_NAME = "OBD-II"
    }
}
