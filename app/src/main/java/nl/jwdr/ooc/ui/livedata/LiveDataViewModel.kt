package nl.jwdr.ooc.ui.livedata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.jwdr.ooc.catalog.BlockReading
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalog.MeasuringBlockCatalog
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.LiveDecodeRuleStore
import nl.jwdr.ooc.diagnostics.EcuScanTarget
import nl.jwdr.ooc.diagnostics.LiveDataCsv
import nl.jwdr.ooc.diagnostics.Obd2Value
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import nl.jwdr.ooc.ui.userMessageFor

/** Persists one finished CSV log; returns the absolute path for sharing. */
fun interface LiveDataCsvStore {
    fun save(fileName: String, content: String): String
}

/** One selectable measuring block of the chosen ECU. */
data class BlockChoice(val number: Int, val title: String)

/** One charted value at a wall-clock instant. */
data class Sample(val timestampMs: Long, val value: Double)

/** One live row: current decoded value plus its recent numeric history. */
data class LiveRow(
    val label: String,
    val unit: String?,
    val display: String,
    val raw: Int?,
    /** False for enumerated (state-label) rows; they are not charted. */
    val isNumeric: Boolean,
    val samples: List<Sample>,
)

/** What the live-data screen shows. */
sealed interface LiveDataUiState {
    /** Upstream flows have not emitted yet. */
    data object Loading : LiveDataUiState

    /** No catalog or no vehicle selected. */
    data object NoVehicle : LiveDataUiState

    /** The selected vehicle's diagnosable ECUs, to pick one. */
    data class PickEcu(val ecus: List<EcuChoice>) : LiveDataUiState

    /** The chosen ECU's measuring blocks, to pick one to poll. */
    data class PickBlock(val ecuName: String, val blocks: List<BlockChoice>) : LiveDataUiState

    /** One measuring block being polled. */
    data class Live(
        val ecuName: String,
        val blockTitle: String,
        val rows: List<LiveRow>,
        val polling: Boolean,
        val error: UserMessage?,
        val logging: Boolean = false,
        /** Path of the last finished CSV log, for the share action. */
        val savedCsvPath: String? = null,
    ) : LiveDataUiState
}

class LiveDataViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
    private val csvStore: LiveDataCsvStore,
    private val ruleStore: LiveDecodeRuleStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<LiveDataUiState>(LiveDataUiState.Loading)
    val state: StateFlow<LiveDataUiState> = _state

    private var definitions: List<EcuDefinition> = emptyList()
    private var currentEcu: EcuDefinition? = null
    private var currentCatalog: MeasuringBlockCatalog? = null

    /** Non-null while in the no-catalog OBD-II fallback mode. */
    private var obd2Targets: List<EcuScanTarget>? = null
    private var pollJob: Job? = null

    private var csvLines: MutableList<String>? = null
    private var logStartMs = 0L

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.selectedVehicle,
                ::Pair,
            ).collectLatest { (summary, selected) ->
                pollJob?.cancel()
                obd2Targets = null
                if (summary == null || selected == null) {
                    _state.value = LiveDataUiState.NoVehicle
                    return@collectLatest
                }
                definitions = repository.canEcusFor(selected)
                _state.value = pickerState()
            }
        }
    }

    /**
     * Enters the generic OBD-II fallback (#14): discovers the emission ECUs
     * on the bus and offers them, without any imported catalog.
     */
    fun useObd2() {
        if (_state.value !is LiveDataUiState.NoVehicle) return
        pollJob?.cancel()
        viewModelScope.launch {
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val targets = diagnosticsManager.discoverObd2Ecus()
                obd2Targets = targets
                _state.value = LiveDataUiState.PickEcu(
                    targets.map { EcuChoice(it.name, OBD2_SYSTEM_NAME) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = LiveDataUiState.NoVehicle
            }
        }
    }

    fun selectEcu(name: String) {
        obd2Targets?.let { targets ->
            targets.find { it.name == name }?.let { pollObd2(it) }
            return
        }
        val definition = definitions.find { it.name == name } ?: return
        pollJob?.cancel()
        viewModelScope.launch {
            currentEcu = definition
            currentCatalog = definition.catalogKey?.let { repository.measuringBlocksFor(it) }
            _state.value = blockPickerState(definition)
        }
    }

    fun selectBlock(number: Int) {
        val definition = currentEcu ?: return
        val address = definition.address as? EcuAddress.Can ?: return
        val catalog = currentCatalog ?: return
        val block = catalog.blocks.find { it.number == number } ?: return
        val rows = catalog.rowsFor(block)
        pollJob?.cancel()
        csvLines = null
        pollJob = viewModelScope.launch {
            _state.value = LiveDataUiState.Live(
                ecuName = definition.name,
                blockTitle = block.title,
                rows = emptyList(),
                polling = true,
                error = null,
            )
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val target = EcuScanTarget(
                    definition.name,
                    address.requestId,
                    address.responseId,
                    secondaryId = address.secondaryId.takeIf { it != 0 },
                    bus = address.bus,
                )
                val decodeRules = definition.catalogKey?.let { ruleStore.rulesFor(it) } ?: emptyMap()
                diagnosticsManager.pollMeasuringBlock(target, block, rows, POLL_INTERVAL, decodeRules)
                    .collect { reading -> onReading(definition.name, reading) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it is LiveDataUiState.Live) {
                        it.copy(polling = false, error = userMessageFor(e))
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun pollObd2(target: EcuScanTarget) {
        pollJob?.cancel()
        csvLines = null
        pollJob = viewModelScope.launch {
            _state.value = LiveDataUiState.Live(
                ecuName = target.name,
                blockTitle = OBD2_SYSTEM_NAME,
                rows = emptyList(),
                polling = true,
                error = null,
            )
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val pids = diagnosticsManager.obd2SupportedPids(target)
                diagnosticsManager.pollObd2Pids(target, pids, POLL_INTERVAL)
                    .collect { values -> onObd2Reading(target.name, values) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it is LiveDataUiState.Live) {
                        it.copy(polling = false, error = userMessageFor(e))
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun onObd2Reading(ecuName: String, values: List<Obd2Value>) {
        val now = clock()
        csvLines?.addAll(LiveDataCsv.obd2Lines(now, ecuName, values))
        _state.update { state ->
            if (state !is LiveDataUiState.Live) return@update state
            val previous = state.rows
            val rows = values.mapIndexed { index, value ->
                val history = previous.getOrNull(index)?.samples.orEmpty()
                LiveRow(
                    label = value.pid.name,
                    unit = value.pid.unit,
                    display = value.display,
                    raw = null,
                    isNumeric = true,
                    samples = (history + Sample(now, value.value))
                        .filter { it.timestampMs >= now - CHART_WINDOW_MS },
                )
            }
            state.copy(rows = rows)
        }
    }

    fun startLogging() {
        val current = _state.value
        if (current !is LiveDataUiState.Live || !current.polling || csvLines != null) return
        logStartMs = clock()
        csvLines = mutableListOf(LiveDataCsv.HEADER)
        _state.value = current.copy(logging = true, savedCsvPath = null)
    }

    fun stopLogging() {
        val lines = csvLines ?: return
        csvLines = null
        val path = csvStore.save(
            "livedata-$logStartMs.csv",
            lines.joinToString("\n", postfix = "\n"),
        )
        _state.update {
            if (it is LiveDataUiState.Live) it.copy(logging = false, savedCsvPath = path) else it
        }
    }

    fun changeBlock() {
        if (obd2Targets != null) {
            // OBD-II has no block picker; fall back to the ECU picker.
            changeEcu()
            return
        }
        val definition = currentEcu ?: return
        stopPolling()
        _state.value = blockPickerState(definition)
    }

    fun changeEcu() {
        stopPolling()
        currentEcu = null
        currentCatalog = null
        _state.value = obd2Targets?.let { targets ->
            LiveDataUiState.PickEcu(targets.map { EcuChoice(it.name, OBD2_SYSTEM_NAME) })
        } ?: pickerState()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        csvLines = null
    }

    private fun onReading(ecuName: String, reading: BlockReading) {
        val now = clock()
        csvLines?.addAll(LiveDataCsv.lines(now, ecuName, reading))
        _state.update { state ->
            if (state !is LiveDataUiState.Live) return@update state
            val previous = state.rows
            val rows = reading.rows.mapIndexed { index, row ->
                val numeric = row.row.states.isEmpty()
                val history = previous.getOrNull(index)?.samples.orEmpty()
                val raw = row.raw
                val samples = if (numeric && raw != null) {
                    (history + Sample(now, raw.toDouble()))
                        .filter { it.timestampMs >= now - CHART_WINDOW_MS }
                } else {
                    history
                }
                LiveRow(
                    label = row.row.label,
                    unit = row.row.unit,
                    display = row.display,
                    raw = row.raw,
                    isNumeric = numeric,
                    samples = samples,
                )
            }
            state.copy(rows = rows)
        }
    }

    private fun pickerState() =
        LiveDataUiState.PickEcu(definitions.map { EcuChoice(it.name, it.systemName) })

    private fun blockPickerState(definition: EcuDefinition) = LiveDataUiState.PickBlock(
        ecuName = definition.name,
        blocks = currentCatalog?.blocks.orEmpty().map { BlockChoice(it.number, it.title) },
    )

    private companion object {
        val POLL_INTERVAL = 500.milliseconds

        /** Chart history horizon. */
        const val CHART_WINDOW_MS = 60_000L

        /** Picker subtitle / block title of the fallback mode. */
        const val OBD2_SYSTEM_NAME = "OBD-II"
    }
}
