package nl.jwdr.ooc.ui.ecus

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
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.CatalogSummary
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.EcuScanStatus
import nl.jwdr.ooc.diagnostics.EcuScanTarget
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.userMessageFor

/** Scan status of one row of the ECU list. */
sealed interface EcuRowStatus {
    /** No scan has probed this ECU yet. */
    data object NotScanned : EcuRowStatus

    /** The running scan is probing this ECU right now. */
    data object Scanning : EcuRowStatus

    /**
     * The ECU answered. [dtcCount] is its stored fault count, or null when
     * it answered with a negative response (alive, fault status unreadable).
     */
    data class Present(val dtcCount: Int?) : EcuRowStatus

    /** The probe timed out: nothing at this address. */
    data object Absent : EcuRowStatus
}

/** One ECU of the selected vehicle. */
data class EcuRow(
    val name: String,
    val systemName: String,
    val status: EcuRowStatus,
)

/** What the ECU list screen shows. */
sealed interface EcuListUiState {
    /** Upstream flows have not emitted yet. */
    data object Loading : EcuListUiState

    /** No catalog imported; point the user to Settings. */
    data object NoCatalog : EcuListUiState

    /** A catalog exists but no vehicle name is chosen yet. */
    data class PickVehicle(val vehicleNames: List<String>) : EcuListUiState

    /** A vehicle name is chosen; offer its catalogued model years. */
    data class PickYear(val vehicleName: String, val years: List<String>) : EcuListUiState

    /** A vehicle + year is chosen and has more than one ECU group; offer them. */
    data class PickEcuGroup(val vehicle: VehicleRef, val groups: List<String>) : EcuListUiState

    /** The selected vehicle/year/ECU-group's ECU list, with per-ECU scan status. */
    data class Ecus(
        val vehicle: VehicleRef,
        val group: String,
        val rows: List<EcuRow>,
        val scanning: Boolean,
        val error: UserMessage?,
    ) : EcuListUiState
}

/** One (summary, vehicleNames, pendingVehicleName, selectedVehicle, selectedGroup) tuple. */
private data class Selection(
    val summary: CatalogSummary?,
    val vehicleNames: List<String>,
    val pendingVehicleName: String?,
    val selectedVehicle: VehicleRef?,
    val selectedGroup: String?,
)

class EcuListViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
) : ViewModel() {

    private val _state = MutableStateFlow<EcuListUiState>(EcuListUiState.Loading)
    val state: StateFlow<EcuListUiState> = _state

    /** Scan targets matching [EcuListUiState.Ecus.rows] one-to-one by index. */
    private var targets: List<EcuScanTarget> = emptyList()
    private var scanJob: Job? = null

    /** Chosen at the vehicle-name step, before a year (and thus a [VehicleRef]) exists. */
    private val pendingVehicleName = MutableStateFlow<String?>(null)

    /** Chosen at the ECU-group step; never persisted, unlike the vehicle/year selection. */
    private val selectedGroup = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.vehicleNames,
                pendingVehicleName,
                repository.selectedVehicle,
                selectedGroup,
                ::Selection,
            ).collectLatest { selection ->
                scanJob?.cancel()
                _state.value = resolveState(selection)
            }
        }
    }

    private suspend fun resolveState(selection: Selection): EcuListUiState {
        val (summary, vehicleNames, pendingName, selectedVehicle, group) = selection
        return when {
            summary == null -> EcuListUiState.NoCatalog
            selectedVehicle == null && pendingName == null -> EcuListUiState.PickVehicle(vehicleNames)
            selectedVehicle == null -> EcuListUiState.PickYear(pendingName!!, repository.yearsFor(pendingName))
            group == null -> pickGroupOrSkip(selectedVehicle)
            else -> ecusState(selectedVehicle, group)
        }
    }

    private suspend fun pickGroupOrSkip(vehicle: VehicleRef): EcuListUiState {
        val groups = repository.groupsFor(vehicle)
        val single = groups.singleOrNull()
        return if (single != null) ecusState(vehicle, single) else EcuListUiState.PickEcuGroup(vehicle, groups)
    }

    private suspend fun ecusState(vehicle: VehicleRef, group: String): EcuListUiState.Ecus {
        val definitions = repository.canEcusFor(vehicle, group)
        targets = definitions.mapNotNull { definition ->
            (definition.address as? EcuAddress.Can)?.let {
                EcuScanTarget(
                    definition.name,
                    it.requestId,
                    it.responseId,
                    // 0 in catalog records that carry no broadcast id.
                    secondaryId = it.secondaryId.takeIf { id -> id != 0 },
                    bus = it.bus,
                )
            }
        }
        return EcuListUiState.Ecus(
            vehicle = vehicle,
            group = group,
            rows = definitions.map { EcuRow(it.name, it.systemName, EcuRowStatus.NotScanned) },
            scanning = false,
            error = null,
        )
    }

    fun selectVehicleName(name: String) {
        pendingVehicleName.value = name
    }

    fun backToVehicleNames() {
        pendingVehicleName.value = null
    }

    fun selectYear(year: String) {
        val name = pendingVehicleName.value ?: return
        viewModelScope.launch { repository.selectVehicle(VehicleRef(year, name)) }
    }

    /** Un-persists the year while keeping the vehicle name, returning to the year picker. */
    fun backToYearPicker() {
        selectedGroup.value = null
        viewModelScope.launch { repository.selectVehicle(null) }
    }

    fun selectGroup(group: String) {
        selectedGroup.value = group
    }

    fun changeVehicle() {
        scanJob?.cancel()
        pendingVehicleName.value = null
        selectedGroup.value = null
        viewModelScope.launch { repository.selectVehicle(null) }
    }

    fun startScan() {
        val current = _state.value
        if (current !is EcuListUiState.Ecus || current.scanning) return
        scanJob = viewModelScope.launch {
            updateEcus { it.copy(scanning = true, error = null, rows = rowsScanning(it.rows, next = 0)) }
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                var index = 0
                diagnosticsManager.scanEcus(targets).collect { result ->
                    val done = index++
                    updateEcus {
                        it.copy(
                            rows = rowsScanning(
                                it.rows.mapIndexed { i, row ->
                                    if (i == done) row.copy(status = result.status.toRowStatus()) else row
                                },
                                next = done + 1,
                            ),
                        )
                    }
                }
                updateEcus { it.copy(scanning = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateEcus { ecus ->
                    ecus.copy(
                        scanning = false,
                        error = userMessageFor(e),
                        rows = ecus.rows.map { row ->
                            if (row.status == EcuRowStatus.Scanning) {
                                row.copy(status = EcuRowStatus.NotScanned)
                            } else {
                                row
                            }
                        },
                    )
                }
            }
        }
    }

    private fun rowsScanning(rows: List<EcuRow>, next: Int): List<EcuRow> =
        rows.mapIndexed { i, row -> if (i == next) row.copy(status = EcuRowStatus.Scanning) else row }

    private fun updateEcus(transform: (EcuListUiState.Ecus) -> EcuListUiState.Ecus) {
        _state.update { if (it is EcuListUiState.Ecus) transform(it) else it }
    }
}

private fun EcuScanStatus.toRowStatus(): EcuRowStatus = when (this) {
    is EcuScanStatus.Present -> EcuRowStatus.Present(dtcCount)
    EcuScanStatus.Absent -> EcuRowStatus.Absent
}
