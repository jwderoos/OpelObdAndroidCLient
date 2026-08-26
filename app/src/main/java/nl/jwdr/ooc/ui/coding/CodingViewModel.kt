package nl.jwdr.ooc.ui.coding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.R
import nl.jwdr.ooc.catalog.CodingTable
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuGroupResolution
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.diagnostics.CodingEntryOutcome
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.diagnosableCanAddress
import nl.jwdr.ooc.diagnostics.toScanTarget
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import nl.jwdr.ooc.ui.userMessageFor

/** One selectable coding table of the picked ECU. */
data class CodingTableChoice(val label: String, val dataIdentifier: Int)

/** One coding entry's row on screen: raw hex, optionally edited, optionally outcome-tagged. */
data class CodingEntryDisplay(
    val id: Int,
    val count: Int,
    val currentHex: String,
    val editedHex: String? = null,
    val outcome: CodingEntryOutcome? = null,
)

/** What the coding screen shows. */
sealed interface CodingUiState {
    data object Loading : CodingUiState
    data object NoVehicle : CodingUiState

    /** The selected vehicle has more than one ECU group; offer them. */
    data class PickEcuGroup(val vehicle: VehicleRef, val groups: List<String>) : CodingUiState
    data class PickEcu(val ecus: List<EcuChoice>) : CodingUiState
    data class PickTable(val ecuName: String, val tables: List<CodingTableChoice>) : CodingUiState
    data class Entries(
        val ecuName: String,
        val tableLabel: String,
        val entries: List<CodingEntryDisplay>,
        val loading: Boolean,
        val writing: Boolean,
        val error: UserMessage? = null,
        val confirmingWrite: Boolean = false,
    ) : CodingUiState
}

/**
 * ECU coding read/write (#18), raw bytes only — see the design spec's "Open
 * questions" note: the DID-to-row mapping isn't established, so this edits
 * whole per-entry hex records, not individual coding rows.
 */
class CodingViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
    /** Defense in depth: [confirmWrite] refuses when this is false even if the screen was somehow reached (design spec safety rule) — the primary gate is hiding the Home entry (see HomeScreen). */
    private val expertMode: StateFlow<Boolean>,
) : ViewModel() {

    private val _state = MutableStateFlow<CodingUiState>(CodingUiState.Loading)
    val state: StateFlow<CodingUiState> = _state

    private var definitions: List<EcuDefinition> = emptyList()
    private var tables: List<CodingTable> = emptyList()
    private var currentDefinition: EcuDefinition? = null
    private var currentTable: CodingTable? = null
    private var loadJob: Job? = null
    private var writeJob: Job? = null

    /** Chosen at the ECU-group step; never persisted, unlike the vehicle selection. */
    private val selectedGroup = MutableStateFlow<Pair<VehicleRef, String>?>(null)

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.selectedVehicle,
                selectedGroup,
                ::Triple,
                // Room re-emits on any catalog-table write; only a real change
                // may reset the screen out from under a read or a write.
            ).distinctUntilChanged().collectLatest { (summary, selected, groupSelection) ->
                loadJob?.cancel()
                // A genuine catalog/vehicle change invalidates the ECU this
                // write targets, so the job is cancelled here on purpose: it
                // must not resurrect a stale Entries state over the picker.
                // The write itself is still atomic — confirmWrite runs the
                // actual batch under NonCancellable.
                writeJob?.cancel()
                currentDefinition = null
                currentTable = null
                if (summary == null || selected == null) {
                    _state.value = CodingUiState.NoVehicle
                    return@collectLatest
                }
                val group = groupSelection?.takeIf { it.first == selected }?.second
                when (val resolution = repository.resolveEcuGroup(selected, group)) {
                    is EcuGroupResolution.NeedsPick -> {
                        definitions = emptyList()
                        _state.value = CodingUiState.PickEcuGroup(selected, resolution.groups)
                    }
                    is EcuGroupResolution.Resolved -> {
                        val withCoding = repository.codingTableKeys()
                        definitions = repository.canEcusFor(selected, resolution.group).filter {
                            it.diagnosableCanAddress() != null && it.catalogKey != null && it.catalogKey in withCoding
                        }
                        _state.value = pickerState()
                    }
                }
            }
        }
    }

    fun selectGroup(group: String) {
        val vehicle = (_state.value as? CodingUiState.PickEcuGroup)?.vehicle ?: return
        selectedGroup.value = vehicle to group
    }

    fun selectEcu(name: String) {
        val definition = definitions.find { it.name == name } ?: return
        val catalogKey = definition.catalogKey ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            tables = repository.codingTablesFor(catalogKey)
            val single = tables.singleOrNull()
            if (single != null) {
                openTable(definition, single)
            } else {
                _state.value = CodingUiState.PickTable(definition.name, tables.map(::tableChoice))
            }
        }
    }

    fun selectTable(dataIdentifier: Int) {
        val current = _state.value as? CodingUiState.PickTable ?: return
        val definition = definitions.find { it.name == current.ecuName } ?: return
        val table = tables.find { it.dataIdentifier == dataIdentifier } ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { openTable(definition, table) }
    }

    fun changeEcu() {
        val current = _state.value
        if (current is CodingUiState.Entries && (current.loading || current.writing)) return
        loadJob?.cancel()
        writeJob?.cancel()
        currentDefinition = null
        currentTable = null
        // Re-derive the group too: it may re-offer the group picker when the
        // vehicle has more than one, exactly like the ECU list does.
        selectedGroup.value = null
        _state.value = pickerState()
    }

    fun changeTable() {
        val current = _state.value
        if (current !is CodingUiState.Entries || current.loading || current.writing) return
        val definition = currentDefinition ?: return
        currentTable = null
        _state.value = if (tables.size > 1) {
            CodingUiState.PickTable(definition.name, tables.map(::tableChoice))
        } else {
            pickerState()
        }
    }

    /** Updates the pending edit for one row; never touches the bus. */
    fun editEntry(id: Int, hex: String) {
        _state.update { s ->
            if (s !is CodingUiState.Entries) return@update s
            s.copy(
                // A previous write's outcome badge describes the value that was
                // on the row before this edit; drop it so it can't be read as
                // the status of what the user is typing now.
                entries = s.entries.map {
                    if (it.id == id) it.copy(editedHex = hex, outcome = null) else it
                },
                // Any earlier complaint (bad hex, expert mode off) refers to a
                // value the user is now changing; don't let it linger.
                error = null,
            )
        }
    }

    /** Opens the write-confirmation dialog, or reports invalid hex instead. */
    fun requestWrite() {
        val current = _state.value as? CodingUiState.Entries ?: return
        if (current.loading || current.writing) return
        val edited = editedEntries(current)
        if (edited.isEmpty()) return
        val badEntry = edited.firstOrNull { parseHex(it.editedHex!!)?.size != it.count }
        if (badEntry != null) {
            _state.value = current.copy(error = UserMessage(R.string.coding_invalid_hex, listOf(badEntry.count)))
            return
        }
        _state.value = current.copy(confirmingWrite = true, error = null)
    }

    fun dismissWrite() {
        _state.update {
            if (it is CodingUiState.Entries) it.copy(confirmingWrite = false, error = null) else it
        }
    }

    fun confirmWrite() {
        val current = _state.value as? CodingUiState.Entries ?: return
        if (!current.confirmingWrite) return
        val definition = currentDefinition ?: return
        val table = currentTable ?: return
        val target = definition.toScanTarget() ?: return
        if (!expertMode.value) {
            _state.value = current.copy(confirmingWrite = false, error = UserMessage(R.string.coding_expert_mode_required))
            return
        }
        val edits = editedEntries(current).associate { it.id to parseHex(it.editedHex!!)!! }
        writeJob?.cancel()
        writeJob = viewModelScope.launch {
            _state.value = current.copy(confirmingWrite = false, writing = true, error = null)
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                // Once the batch starts it must run to the end: an aborted
                // write leaves a half-applied coding record on the ECU, the
                // exact failure this feature's safety design exists to
                // prevent. Cancelling the scope (navigating away, catalog
                // change) may therefore only discard the *reporting* below,
                // never the write itself.
                val result = withContext(NonCancellable) {
                    diagnosticsManager.writeCoding(target, table, edits)
                }
                // NonCancellable suppressed cancellation for the batch; honour
                // it now, so a torn-down screen isn't resurrected by the state
                // write below.
                ensureActive()
                val outcomeById = result.outcomes.associateBy { it.id }
                _state.value = current.copy(
                    entries = result.entries.map { read ->
                        CodingEntryDisplay(
                            id = read.id,
                            count = read.bytes.size,
                            currentHex = toHex(read.bytes),
                            editedHex = null,
                            outcome = outcomeById[read.id],
                        )
                    },
                    writing = false,
                    confirmingWrite = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s is CodingUiState.Entries) s.copy(writing = false, error = userMessageFor(e)) else s
                }
            }
        }
    }

    private suspend fun openTable(definition: EcuDefinition, table: CodingTable) {
        val target = definition.toScanTarget() ?: return
        currentDefinition = definition
        currentTable = table
        val label = tableChoice(table).label
        _state.value = CodingUiState.Entries(definition.name, label, emptyList(), loading = true, writing = false)
        try {
            if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                diagnosticsManager.connect()
            }
            val result = diagnosticsManager.readCoding(target, table)
            _state.value = CodingUiState.Entries(
                ecuName = definition.name,
                tableLabel = label,
                entries = result.entries.map { CodingEntryDisplay(it.id, it.bytes.size, toHex(it.bytes)) },
                loading = false,
                writing = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = CodingUiState.Entries(
                ecuName = definition.name,
                tableLabel = label,
                entries = emptyList(),
                loading = false,
                writing = false,
                error = userMessageFor(e),
            )
        }
    }

    private fun editedEntries(state: CodingUiState.Entries) =
        state.entries.filter { it.editedHex != null && it.editedHex != it.currentHex }

    private fun pickerState() = CodingUiState.PickEcu(definitions.map { EcuChoice(it.name, it.systemName) })

    private fun tableChoice(table: CodingTable) =
        CodingTableChoice(label = "0x%04X".format(table.dataIdentifier), dataIdentifier = table.dataIdentifier)
}

private fun parseHex(hex: String): ByteArray? {
    val clean = hex.trim()
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return try {
        ByteArray(clean.length / 2) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
