package nl.jwdr.ooc.ui.faultcodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.R
import nl.jwdr.ooc.catalogstore.CatalogEntity
import nl.jwdr.ooc.catalogstore.CatalogFileEntity
import nl.jwdr.ooc.catalogstore.CatalogPayload
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuEntity
import nl.jwdr.ooc.catalogstore.FakeCatalogDao
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaultCodesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(transport: ObdTransport, initialEcuName: String? = null) =
        FaultCodesViewModel(repository, DiagnosticsManager(transport), initialEcuName)

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private fun readRequest(requestId: Int) = frame(requestId, 0x04, 0x18, 0x02, 0xFF, 0x00)

    private fun canEcu(name: String, requestId: Int, catalogKey: String? = null) = EcuEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        modelYear = "2005",
        vehicle = "Astra-H",
        groupName = "Body",
        name = name,
        systemName = "$name system",
        protocol = "CAN",
        builtinFunction = null,
        catalogKey = catalogKey,
        addressType = "CAN",
        canBus = "HSCAN",
        bitRateTenthsKbps = 5000,
        requestId = requestId,
        secondaryId = 0,
        responseId = requestId + 8,
        baudRate = null,
        klineAddress = null,
        initType = null,
        extra = null,
    )

    private fun errorCodesFile(fileKey: String, text: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "ERROR_CODES",
        fileKey = fileKey,
        fileName = "$fileKey.txt",
        content = text.toByteArray(Charsets.ISO_8859_1),
    )

    private suspend fun storeCatalog(
        ecus: List<EcuEntity>,
        files: List<CatalogFileEntity> = emptyList(),
        selected: Boolean = true,
    ) {
        dao.replaceCatalog(
            CatalogPayload(
                catalog = CatalogEntity(
                    label = "test",
                    sourceHash = "h",
                    importedAtEpochMillis = 1L,
                    selectedModelYear = if (selected) "2005" else null,
                    selectedVehicle = if (selected) "Astra-H" else null,
                ),
                ecus = ecus,
                files = files,
            ),
        )
    }

    @Test
    fun `without a selected vehicle the screen points to the ECU list`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(FaultCodesUiState.NoVehicle, viewModel.state.value)
    }

    @Test
    fun `with a vehicle and no target ECU the screen offers the ECU picker`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("ABS", 0x241), canEcu("Engine", 0x7E0)))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            FaultCodesUiState.PickEcu(
                listOf(
                    EcuChoice("ABS", "ABS system"),
                    EcuChoice("Engine", "Engine system"),
                ),
            ),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting an ECU reads its DTCs with catalog texts`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(canEcu("Engine", 0x7E0, catalogKey = "ENG")),
            files = listOf(errorCodesFile("ENG", "P0016\n-00\tCrankshaft/Camshaft Correlation\n")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        // One DTC: 0x0016, symptom 0x00.
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x00, 0x16, 0x00))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertEquals("Engine", state.ecuName)
        assertFalse(state.reading)
        assertNull(state.error)
        assertEquals(
            listOf(FaultEntry("P0016", 0, "Crankshaft/Camshaft Correlation")),
            state.entries,
        )
    }

    @Test
    fun `a DTC missing from the catalog keeps its code without text`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x90, 0x00, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertEquals(listOf(FaultEntry("B1000", 2, text = null)), state.entries)
    }

    @Test
    fun `a target ECU passed at navigation reads immediately`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x02, 0x58, 0x00))
        val viewModel = viewModel(transport, initialEcuName = "Engine")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertEquals("Engine", state.ecuName)
        assertEquals(emptyList<FaultEntry>(), state.entries)
    }

    @Test
    fun `a failing read surfaces a user-readable error`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 18 11: serviceNotSupported.
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x18, 0x11))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertFalse(state.reading)
        assertEquals(R.string.error_negative_response, state.error!!.resId)
        assertEquals(emptyList<FaultEntry>(), state.entries)
    }

    @Test
    fun `changing the ECU returns to the picker`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x02, 0x58, 0x00))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.changeEcu()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            FaultCodesUiState.PickEcu(listOf(EcuChoice("Engine", "Engine system"))),
            viewModel.state.value,
        )
    }
}
