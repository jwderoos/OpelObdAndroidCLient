package nl.jwdr.ooc.ui.outputtests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.catalog.DisplayTagBindings
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalog.OutputTest
import nl.jwdr.ooc.catalog.OutputTestType
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.EcuScanTarget
import nl.jwdr.ooc.diagnostics.OutputTestRun
import nl.jwdr.ooc.diagnostics.TagReadout
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.ui.UserMessage
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import nl.jwdr.ooc.ui.userMessageFor

/** One listed output test of the selected ECU. */
data class OutputTestChoice(
    val title: String,
    val type: OutputTestType,
    /** Operator preconditions to confirm before starting (safety gate). */
    val preTestInstructions: List<String>,
)

/** What the output-test screen shows. */
sealed interface OutputTestsUiState {
    /** Upstream flows have not emitted yet. */
    data object Loading : OutputTestsUiState

    /** No catalog or no vehicle selected; point the user to the ECU list. */
    data object NoVehicle : OutputTestsUiState

    /** The selected vehicle's diagnosable ECUs, to pick one. */
    data class PickEcu(val ecus: List<EcuChoice>) : OutputTestsUiState

    /** The catalog output tests of one ECU. */
    data class Tests(
        val ecuName: String,
        val tests: List<OutputTestChoice>,
        val error: UserMessage? = null,
        /** Index of the test awaiting start confirmation, if any. */
        val confirming: Int? = null,
        /** A confirmed start (before-test phase) is on the bus. */
        val starting: Boolean = false,
    ) : OutputTestsUiState

    /** One test's interactive phase. */
    data class Running(
        val ecuName: String,
        val test: OutputTest,
        /** The actuator is activated (meaningful for ONOFF tests). */
        val active: Boolean = false,
        /** A control command is on the bus. */
        val busy: Boolean = false,
        val error: UserMessage? = null,
        /** Live display-tag readings; empty when the test has none. */
        val readouts: List<TagReadout> = emptyList(),
    ) : OutputTestsUiState
}

class OutputTestsViewModel(
    private val repository: CatalogRepository,
    private val diagnosticsManager: DiagnosticsManager,
) : ViewModel() {

    private val _state = MutableStateFlow<OutputTestsUiState>(OutputTestsUiState.Loading)
    val state: StateFlow<OutputTestsUiState> = _state

    /** The selected vehicle's CAN ECU definitions, keyed off the picker. */
    private var definitions: List<EcuDefinition> = emptyList()

    /** The domain tests behind the listed [OutputTestChoice]s. */
    private var tests: List<OutputTest> = emptyList()

    private var run: OutputTestRun? = null
    private var readoutsJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                repository.summary,
                repository.selectedVehicle,
                ::Pair,
                // Room re-emits on any catalog-table write; only a real
                // change may kick the user out of a running test.
            ).distinctUntilChanged().collectLatest { (summary, selected) ->
                finishRun()
                if (summary == null || selected == null) {
                    _state.value = OutputTestsUiState.NoVehicle
                    return@collectLatest
                }
                definitions = repository.canEcusFor(selected)
                _state.value = pickerState()
            }
        }
    }

    fun selectEcu(name: String) {
        val definition = definitions.find { it.name == name } ?: return
        viewModelScope.launch {
            val catalog = definition.catalogKey?.let { repository.outputTestsFor(it) }
            tests = catalog?.tests.orEmpty()
            _state.value = testsState(definition.name)
        }
    }

    fun changeEcu() {
        val current = _state.value
        if (current is OutputTestsUiState.Tests && current.starting) return
        _state.value = pickerState()
    }

    /** Opens the start-confirmation dialog for the test at [index]. */
    fun requestStart(index: Int) {
        _state.update {
            if (it is OutputTestsUiState.Tests && !it.starting && index in tests.indices) {
                it.copy(confirming = index)
            } else {
                it
            }
        }
    }

    fun dismissStart() {
        _state.update {
            if (it is OutputTestsUiState.Tests) it.copy(confirming = null) else it
        }
    }

    fun confirmStart() {
        val current = _state.value
        if (current !is OutputTestsUiState.Tests || current.starting) return
        val index = current.confirming ?: return
        val test = tests.getOrNull(index) ?: return
        val definition = definitions.find { it.name == current.ecuName } ?: return
        val address = definition.address as? EcuAddress.Can ?: return
        viewModelScope.launch {
            _state.value = current.copy(confirming = null, starting = true, error = null)
            try {
                if (diagnosticsManager.connectionState.value !is ConnectionState.Ready) {
                    diagnosticsManager.connect()
                }
                val bindings = if (test.displayTags.isEmpty()) {
                    emptyList()
                } else {
                    definition.catalogKey?.let { repository.measuringBlocksFor(it) }
                        ?.let { DisplayTagBindings.resolve(it, test.displayTags) }
                        .orEmpty()
                }
                val started = diagnosticsManager.startOutputTest(
                    EcuScanTarget(
                        definition.name,
                        address.requestId,
                        address.responseId,
                        // 0 in catalog records that carry no broadcast id.
                        address.secondaryId.takeIf { it != 0 },
                    ),
                    test,
                    bindings,
                )
                // The catalog/vehicle selection may have changed while
                // connect/start were suspended; don't resurrect a stale run.
                val latest = _state.value
                if (latest !is OutputTestsUiState.Tests || !latest.starting) {
                    withContext(NonCancellable) { started.finish() }
                    return@launch
                }
                run = started
                readoutsJob = viewModelScope.launch {
                    started.readouts.collect { readouts ->
                        _state.update { s ->
                            if (s is OutputTestsUiState.Running) s.copy(readouts = readouts) else s
                        }
                    }
                }
                _state.value =
                    OutputTestsUiState.Running(current.ecuName, test, readouts = started.readouts.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = current.copy(
                    confirming = null,
                    starting = false,
                    error = userMessageFor(e),
                )
            }
        }
    }

    fun activate() = control(activate = true)

    fun deactivate() = control(activate = false)

    /** Ends the running test: teardown records, then back to the test list. */
    fun stop() {
        val current = _state.value as? OutputTestsUiState.Running ?: return
        if (current.busy) return
        viewModelScope.launch {
            _state.update { s ->
                if (s is OutputTestsUiState.Running) s.copy(busy = true, error = null) else s
            }
            try {
                finishRun()
                _state.value = testsState(current.ecuName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The session is closed even when teardown fails; staying in
                // Running would leave dead controls.
                _state.value = testsState(current.ecuName, error = userMessageFor(e))
            }
        }
    }

    private fun control(activate: Boolean) {
        val current = _state.value as? OutputTestsUiState.Running ?: return
        val run = run ?: return
        if (current.busy) return
        viewModelScope.launch {
            _state.update { s ->
                if (s is OutputTestsUiState.Running) s.copy(busy = true, error = null) else s
            }
            try {
                if (activate) run.activate() else run.deactivate()
                _state.update { s ->
                    if (s is OutputTestsUiState.Running) {
                        s.copy(active = activate, busy = false, error = null)
                    } else {
                        s
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s is OutputTestsUiState.Running) {
                        s.copy(busy = false, error = userMessageFor(e))
                    } else {
                        s
                    }
                }
            }
        }
    }

    private suspend fun finishRun() {
        readoutsJob?.cancel()
        readoutsJob = null
        run?.let { active ->
            run = null
            // Not cancellable: an interrupted teardown would leave the
            // output actuated with the session gone.
            withContext(NonCancellable) { active.finish() }
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here; tear the ECU down (it may
        // still be actuating an output) on an independent best-effort scope.
        // Teardown failures have no UI left to report to; swallow them.
        run?.let { active ->
            run = null
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { active.finish() }
            }
        }
    }

    private fun pickerState() =
        OutputTestsUiState.PickEcu(definitions.map { EcuChoice(it.name, it.systemName) })

    private fun testsState(ecuName: String, error: UserMessage? = null) = OutputTestsUiState.Tests(
        ecuName = ecuName,
        tests = tests.map { OutputTestChoice(it.title, it.type, it.preTestInstructions) },
        error = error,
    )
}
