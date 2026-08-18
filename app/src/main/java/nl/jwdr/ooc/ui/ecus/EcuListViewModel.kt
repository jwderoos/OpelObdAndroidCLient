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

    /** A catalog exists but no vehicle is selected yet. */
    data class PickVehicle(val vehicles: List<VehicleRef>) : EcuListUiState

    /** The selected vehicle's ECU list, with per-ECU scan status. */
    data class Ecus(
        val vehicle: VehicleRef,
        val rows: List<EcuRow>,
        val scanning: Boolean,
        val error: UserMessage?,
    ) : EcuListUiState
}

class EcuListViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
) : ViewModel() {

    private val _state = MutableStateFlow<EcuListUiState>(EcuListUiState.Loading)
    val state: StateFlow<EcuListUiState> = _state

    /** Scan targets matching [EcuListUiState.Ecus.rows] one-to-one by index. */
    private var targets: List<EcuScanTarget> = emptyList()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.vehicles,
                repository.selectedVehicle,
                ::Triple,
            ).collectLatest { (summary, vehicles, selected) ->
                scanJob?.cancel()
                _state.value = when {
                    summary == null -> EcuListUiState.NoCatalog
                    selected == null -> EcuListUiState.PickVehicle(vehicles)
                    else -> ecusState(selected)
                }
            }
        }
    }

    private suspend fun ecusState(selected: VehicleRef): EcuListUiState.Ecus {
        val definitions = repository.canEcusFor(selected)
        targets = definitions.mapNotNull { definition ->
            (definition.address as? EcuAddress.Can)?.let {
                EcuScanTarget(definition.name, it.requestId, it.responseId)
            }
        }
        return EcuListUiState.Ecus(
            vehicle = selected,
            rows = definitions.map { EcuRow(it.name, it.systemName, EcuRowStatus.NotScanned) },
            scanning = false,
            error = null,
        )
    }

    fun selectVehicle(ref: VehicleRef) {
        viewModelScope.launch { repository.selectVehicle(ref) }
    }

    fun changeVehicle() {
        scanJob?.cancel()
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
