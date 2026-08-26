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
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuGroupResolution
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.EcuScanTarget
import nl.jwdr.ooc.diagnostics.diagnosableCanAddress
import nl.jwdr.ooc.diagnostics.toScanTarget
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

    /** The selected vehicle has more than one ECU group; offer them. */
    data class PickEcuGroup(val vehicle: VehicleRef, val groups: List<String>) : FaultCodesUiState

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

    /** Chosen at the ECU-group step; never persisted, unlike the vehicle selection. */
    private val selectedGroup = MutableStateFlow<Pair<VehicleRef, String>?>(null)

    /** The vehicle currently being resolved; null when none is selected. */
    private var currentVehicle: VehicleRef? = null

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.selectedVehicle,
                selectedGroup,
                ::Triple,
            ).collectLatest { (summary, selected, groupSelection) ->
                readJob?.cancel()
                obd2Targets = null
                if (summary == null || selected == null) {
                    currentVehicle = null
                    _state.value = FaultCodesUiState.NoVehicle
                    return@collectLatest
                }
                currentVehicle = selected
                val pending = pendingEcuName
                if (pending != null) {
                    pendingEcuName = null
                    // The deep-link target (ECU list drill-in, issue #32) may
                    // belong to any group: search the flat catalog rather than
                    // making it wait on a group pick it didn't ask for. Note
                    // selectedGroup is deliberately left untouched here — it's
                    // a combine() input, and mutating it from inside this very
                    // collectLatest would self-trigger a re-entrant emission
                    // that cancels the read() job just launched below.
                    val flatDefinitions = repository.canEcusFor(selected)
                        .filter { it.diagnosableCanAddress() != null }
                    val target = flatDefinitions.find { it.name == pending }
                    if (target != null) {
                        definitions = flatDefinitions.filter { it.group == target.group }
                        read(target)
                        return@collectLatest
                    }
                }
                val group = groupSelection?.takeIf { it.first == selected }?.second
                resolveGroupAndShowPicker(selected, group)
            }
        }
    }

    /** Drop catalog placeholder rows (zero address, VIRTUAL/CHCAN bus): they must not be offered or read (issue #32). */
    private suspend fun resolveGroupAndShowPicker(selected: VehicleRef, group: String?) {
        when (val resolution = repository.resolveEcuGroup(selected, group)) {
            is EcuGroupResolution.NeedsPick -> {
                definitions = emptyList()
                _state.value = FaultCodesUiState.PickEcuGroup(selected, resolution.groups)
            }
            is EcuGroupResolution.Resolved -> {
                definitions = repository.canEcusFor(selected, resolution.group)
                    .filter { it.diagnosableCanAddress() != null }
                _state.value = pickerState()
            }
        }
    }

    fun selectGroup(group: String) {
        val vehicle = (_state.value as? FaultCodesUiState.PickEcuGroup)?.vehicle ?: return
        selectedGroup.value = vehicle to group
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
        val target = definition.toScanTarget() ?: return
        clearWith(current) {
            val remaining = diagnosticsManager.clearDtcs(target)
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
        obd2Targets?.let { targets ->
            _state.value = FaultCodesUiState.PickEcu(targets.map { EcuChoice(it.name, OBD2_SYSTEM_NAME) })
            return
        }
        val vehicle = currentVehicle ?: return
        // Re-derive the group too: it may re-offer the group picker when the
        // vehicle has more than one, exactly like the ECU list does. Resolved
        // directly (not just by resetting selectedGroup and waiting on the
        // combine to re-fire) so this also works right after a deep-link read,
        // which never populates selectedGroup in the first place.
        selectedGroup.value = null
        viewModelScope.launch { resolveGroupAndShowPicker(vehicle, null) }
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
        val target = definition.toScanTarget() ?: return
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
