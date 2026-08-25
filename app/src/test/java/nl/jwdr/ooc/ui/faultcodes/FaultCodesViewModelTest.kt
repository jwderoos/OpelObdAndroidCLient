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
import org.junit.Assert.assertTrue
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

    private fun canEcu(name: String, requestId: Int, catalogKey: String? = null, secondaryId: Int = 0) = EcuEntity(
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
        secondaryId = secondaryId,
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
    fun `a GMLAN-addressed ECU reads its DTCs via readDiagnosticInformation`() = runTest(dispatcher) {
        // GMLAN response id is requestId + 0x400 (real addressing), not the
        // canEcu() default of +8 (that formula fits this file's KWP2000 tests).
        storeCatalog(ecus = listOf(canEcu("AHL", 0x249, secondaryId = 0x549).copy(responseId = 0x649)))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x249, 0x01, 0x20)).respondWith(frame(0x649, 0x01, 0x60))
        transport.onFrame(frame(0x249, 0x03, 0xA9, 0x81, 0x12)).respondWith(
            frame(0x549, 0x81, 0x93, 0x25, 0x03, 0x92),
            frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("AHL")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        // 0x9325 via SAE J2012 (DtcCode.format): top 2 bits '10' -> 'B', next 2 bits '01' -> '1'.
        assertEquals(listOf(FaultEntry("B1325", 3, text = null)), state.entries)
    }

    @Test
    fun `confirming a clear on a GMLAN ECU shows the fresh post-clear state, not the cached pre-clear read`() =
        runTest(dispatcher) {
            storeCatalog(ecus = listOf(canEcu("AHL", 0x249, secondaryId = 0x549).copy(responseId = 0x649)))
            val transport = FakeEcuTransport(backgroundScope)
            transport.onFrame(frame(0x249, 0x01, 0x20)).respondWith(frame(0x649, 0x01, 0x60))
            transport.onFrame(frame(0x249, 0x01, 0x04)).respondWith(frame(0x649, 0x01, 0x44))
            var readCount = 0
            transport.onFrame(frame(0x249, 0x03, 0xA9, 0x81, 0x12)).respondBy {
                readCount++
                if (readCount == 1) {
                    listOf(
                        frame(0x549, 0x81, 0x93, 0x25, 0x03, 0x92),
                        frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92),
                    )
                } else {
                    listOf(frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92))
                }
            }
            val viewModel = viewModel(transport)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.selectEcu("AHL")
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.requestClear()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.confirmClear()
            dispatcher.scheduler.advanceUntilIdle()

            // The post-clear read must not see the first read's UUDT frames,
            // which are still in the transport's replay buffer.
            val state = viewModel.state.value as FaultCodesUiState.Faults
            assertEquals(emptyList<FaultEntry>(), state.entries)
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

    private fun clearRequest(requestId: Int) = frame(requestId, 0x03, 0x14, 0xFF, 0x00)

    /** A transport scripted with one stored DTC that disappears once cleared. */
    private fun transportWithClearableDtc(
        transport: FakeEcuTransport,
        clearResponse: CanFrame = frame(0x7E8, 0x01, 0x54),
    ) {
        var cleared = false
        transport.onFrame(clearRequest(0x7E0)).respondBy {
            cleared = true
            listOf(clearResponse)
        }
        transport.onFrame(readRequest(0x7E0)).respondBy {
            if (cleared) {
                listOf(frame(0x7E8, 0x02, 0x58, 0x00))
            } else {
                listOf(frame(0x7E8, 0x05, 0x58, 0x01, 0x00, 0x16, 0x00))
            }
        }
    }

    @Test
    fun `requesting a clear asks for confirmation without touching the bus`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transportWithClearableDtc(transport)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestClear()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertTrue(state.confirmingClear)
        assertFalse(transport.sentFrames.contains(clearRequest(0x7E0)))
    }

    @Test
    fun `dismissing the confirmation clears nothing`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transportWithClearableDtc(transport)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestClear()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissClear()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertFalse(state.confirmingClear)
        assertFalse(transport.sentFrames.contains(clearRequest(0x7E0)))
        assertEquals(1, state.entries.size)
    }

    @Test
    fun `confirming the clear sends it and re-reads the ECU`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        transportWithClearableDtc(transport)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestClear()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmClear()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertTrue(transport.sentFrames.contains(clearRequest(0x7E0)))
        assertFalse(state.confirmingClear)
        assertFalse(state.clearing)
        assertFalse(state.reading)
        assertNull(state.error)
        assertEquals(emptyList<FaultEntry>(), state.entries)
    }

    @Test
    fun `a failing clear surfaces an error and keeps the fault list`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 14 11: serviceNotSupported.
        transportWithClearableDtc(transport, clearResponse = frame(0x7E8, 0x03, 0x7F, 0x14, 0x11))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestClear()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmClear()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertFalse(state.clearing)
        assertEquals(R.string.error_negative_response, state.error!!.resId)
        assertEquals(1, state.entries.size)
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

    // Generic OBD-II fallback (#14)

    private fun obd2Transport(scope: kotlinx.coroutines.CoroutineScope): FakeEcuTransport {
        val transport = FakeEcuTransport(scope)
        // Functional probe: one ECU answers.
        transport.onFrame(frame(0x7DF, 0x02, 0x01, 0x00))
            .respondWith(frame(0x7E8, 0x06, 0x41, 0x00, 0x08, 0x10, 0x00, 0x00))
        var cleared = false
        transport.onFrame(frame(0x7E0, 0x01, 0x04)).respondBy {
            cleared = true
            listOf(frame(0x7E8, 0x01, 0x44))
        }
        transport.onFrame(frame(0x7E0, 0x01, 0x03)).respondBy {
            if (cleared) {
                listOf(frame(0x7E8, 0x02, 0x43, 0x00))
            } else {
                // One stored DTC: P0143.
                listOf(frame(0x7E8, 0x04, 0x43, 0x01, 0x01, 0x43))
            }
        }
        return transport
    }

    @Test
    fun `without a vehicle the OBD-II fallback discovers ECUs`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(obd2Transport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(FaultCodesUiState.NoVehicle, viewModel.state.value)

        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            FaultCodesUiState.PickEcu(listOf(EcuChoice("0x7E0", "OBD-II"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting an OBD-II ECU reads its emission DTCs`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(obd2Transport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("0x7E0")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertEquals("0x7E0", state.ecuName)
        assertFalse(state.reading)
        assertNull(state.error)
        assertEquals(listOf(FaultEntry("P0143", 0, text = null)), state.entries)
    }

    @Test
    fun `clearing in OBD-II mode stays behind the confirmation gate`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val transport = obd2Transport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("0x7E0")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestClear()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue((viewModel.state.value as FaultCodesUiState.Faults).confirmingClear)
        assertFalse(transport.sentFrames.contains(frame(0x7E0, 0x01, 0x04)))

        viewModel.confirmClear()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        assertTrue(transport.sentFrames.contains(frame(0x7E0, 0x01, 0x04)))
        assertEquals(emptyList<FaultEntry>(), state.entries)
    }
}
