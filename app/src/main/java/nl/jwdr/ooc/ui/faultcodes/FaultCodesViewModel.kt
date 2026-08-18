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

    fun selectEcu(name: String) {
        val definition = definitions.find { it.name == name } ?: return
        read(definition)
    }

    fun refresh() {
        val current = _state.value
        if (current !is FaultCodesUiState.Faults || current.reading || current.clearing) return
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
        val definition = definitions.find { it.name == current.ecuName } ?: return
        val address = definition.address as? EcuAddress.Can ?: return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _state.value = current.copy(confirmingClear = false, clearing = true, error = null)
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val remaining = diagnosticsManager.clearDtcs(
                    EcuScanTarget(definition.name, address.requestId, address.responseId),
                )
                _state.value = FaultCodesUiState.Faults(
                    ecuName = definition.name,
                    entries = faultEntries(definition, remaining),
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
        _state.value = pickerState()
    }

    private fun pickerState() =
        FaultCodesUiState.PickEcu(definitions.map { EcuChoice(it.name, it.systemName) })

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
                val target = EcuScanTarget(definition.name, address.requestId, address.responseId)
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
}
