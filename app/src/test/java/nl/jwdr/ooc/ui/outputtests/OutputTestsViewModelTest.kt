package nl.jwdr.ooc.ui.outputtests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.catalog.OutputTestType
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
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputTestsViewModelTest {

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

    private fun viewModel(transport: ObdTransport) =
        OutputTestsViewModel(repository, DiagnosticsManager(transport))

    /**
     * Bounded advancement for phases where a run's periodic all-nodes
     * tester-present task is scheduled: advanceUntilIdle would re-schedule
     * it forever and never go idle.
     */
    private fun settle() {
        dispatcher.scheduler.advanceTimeBy(1_500)
        dispatcher.scheduler.runCurrent()
    }

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private fun canEcu(
        name: String,
        requestId: Int,
        catalogKey: String? = null,
        secondaryId: Int = 0,
    ) = EcuEntity(
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

    private fun outputTestsFile(fileKey: String, text: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "OUTPUT_TESTS",
        fileKey = fileKey,
        fileName = "$fileKey.SCR.txt",
        content = text.toByteArray(Charsets.ISO_8859_1),
    )

    private fun measuringBlocksFile(fileKey: String, text: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "MEASURING_BLOCKS",
        fileKey = fileKey,
        fileName = "$fileKey.MBF.txt",
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

    private val scriptText = """
        ;KW2000
        Return Pump Relay Test
        [TESTTYPE=ONOFF]
        [begin]
        ##Ignition on, engine off##
        BeforeTest=	0x03,0xAE,0x01,0x00,0x00,0x00,0x00,0x00,
        GoActivate=	0x06,0xAE,0x02,0x02,0x00,0x00,0x00,0x00,
        DeActivate=	0x06,0xAE,0x02,0x00,0x00,0x00,0x00,0x00,
        AfterTest=	0x03,0xAE,0x01,0x0C,0x00,0x00,0x00,0x00,
        [end]
    """.trimIndent()

    private val beforeFrame = frame(0x240, 0x03, 0xAE, 0x01, 0x00)
    private val activateFrame = frame(0x240, 0x06, 0xAE, 0x02, 0x02, 0x00, 0x00, 0x00)
    private val deactivateFrame = frame(0x240, 0x06, 0xAE, 0x02, 0x00, 0x00, 0x00, 0x00)
    private val afterFrame = frame(0x240, 0x03, 0xAE, 0x01, 0x0C)

    private fun scriptedTransport(scope: kotlinx.coroutines.CoroutineScope): FakeEcuTransport {
        val transport = FakeEcuTransport(scope)
        transport.onFrame(beforeFrame).respondWith(frame(0x248, 0x02, 0xEE, 0x01))
        transport.onFrame(activateFrame).respondWith(frame(0x248, 0x02, 0xEE, 0x02))
        transport.onFrame(deactivateFrame).respondWith(frame(0x248, 0x02, 0xEE, 0x02))
        transport.onFrame(afterFrame).respondWith(frame(0x248, 0x02, 0xEE, 0x01))
        return transport
    }

    @Test
    fun `without a selected vehicle the screen points to the ECU list`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("REC", 0x240, "RECKEY")), selected = false)
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(OutputTestsUiState.NoVehicle, viewModel.state.value)
    }

    @Test
    fun `the ECU picker lists only modules that have an output-test file`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("ABS", 0x241), // no catalogKey / no SCR -> no output tests
                canEcu("REC", 0x240, "RECKEY"),
            ),
            files = listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            OutputTestsUiState.PickEcu(listOf(EcuChoice("REC", "REC system"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `an ECU without an output-test file is not offered at all`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("REC", 0x240, "RECKEY")))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(OutputTestsUiState.PickEcu(emptyList()), viewModel.state.value)
    }

    @Test
    fun `selecting an ECU lists its catalog output tests`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as OutputTestsUiState.Tests
        assertEquals("REC", state.ecuName)
        assertEquals(1, state.tests.size)
        assertEquals("Return Pump Relay Test", state.tests[0].title)
        assertEquals(OutputTestType.ONOFF, state.tests[0].type)
        assertEquals(listOf("Ignition on, engine off"), state.tests[0].preTestInstructions)
    }

    @Test
    fun `an ECU whose output-test file defines no tests shows an empty list`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", "")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as OutputTestsUiState.Tests
        assertEquals(emptyList<OutputTestChoice>(), state.tests)
    }

    @Test
    fun `a test starts only after explicit confirmation`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = scriptedTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestStart(0)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, (viewModel.state.value as OutputTestsUiState.Tests).confirming)
        assertTrue("nothing on the bus before confirmation", transport.sentFrames.isEmpty())

        viewModel.confirmStart()
        settle()

        val running = viewModel.state.value as OutputTestsUiState.Running
        assertEquals("Return Pump Relay Test", running.test.title)
        assertTrue(transport.sentFrames.contains(beforeFrame))

        viewModel.stop()
        settle()
    }

    @Test
    fun `dismissing the confirmation keeps the test off the bus`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = scriptedTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestStart(0)
        viewModel.dismissStart()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, (viewModel.state.value as OutputTestsUiState.Tests).confirming)
        assertTrue(transport.sentFrames.isEmpty())
    }

    @Test
    fun `activate and deactivate drive the actuator and the active flag`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = scriptedTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestStart(0)
        viewModel.confirmStart()
        settle()

        viewModel.activate()
        settle()
        assertTrue(transport.sentFrames.contains(activateFrame))
        assertTrue((viewModel.state.value as OutputTestsUiState.Running).active)

        viewModel.deactivate()
        settle()
        assertTrue(transport.sentFrames.contains(deactivateFrame))
        assertFalse((viewModel.state.value as OutputTestsUiState.Running).active)

        viewModel.stop()
        settle()
    }

    @Test
    fun `stopping runs the teardown and returns to the test list`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = scriptedTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestStart(0)
        viewModel.confirmStart()
        settle()

        viewModel.stop()
        settle()

        assertTrue(transport.sentFrames.contains(afterFrame))
        val state = viewModel.state.value as OutputTestsUiState.Tests
        assertEquals("REC", state.ecuName)
        assertNotNull(state.tests)
    }

    @Test
    fun `a failed stop still leaves the running screen`() = runTest(dispatcher) {
        // The session is closed even when teardown fails, so staying in
        // Running would leave dead controls; return to the list with an error.
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(beforeFrame).respondWith(frame(0x248, 0x02, 0xEE, 0x01))
        // 7F AE 22: conditionsNotCorrect on the teardown record.
        transport.onFrame(afterFrame).respondWith(frame(0x248, 0x03, 0x7F, 0xAE, 0x22))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.requestStart(0)
        viewModel.confirmStart()
        settle()

        viewModel.stop()
        settle()

        val state = viewModel.state.value as OutputTestsUiState.Tests
        assertNotNull(state.error)
    }

    @Test
    fun `a failed start surfaces an error and stays on the test list`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY")),
            listOf(outputTestsFile("RECKEY", scriptText)),
        )
        val transport = FakeEcuTransport(backgroundScope)
        // 7F AE 11: serviceNotSupported.
        transport.onFrame(beforeFrame).respondWith(frame(0x248, 0x03, 0x7F, 0xAE, 0x11))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestStart(0)
        viewModel.confirmStart()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as OutputTestsUiState.Tests
        assertNotNull(state.error)
    }

    private val taggedScriptText = """
        ;KW2000
        Pump Test With Readouts
        [TESTTYPE=ONOFF]
        [begin]
        **PUMP**
        BeforeTest=	0x04,0xAA,0x03,0x10,0x11,0x00,0x00,0x00,
        BeforeTest=	0x03,0xAE,0x01,0x00,0x00,0x00,0x00,0x00,
        GoActivate=	0x06,0xAE,0x02,0x02,0x00,0x00,0x00,0x00,
        DeActivate=	0x06,0xAE,0x02,0x00,0x00,0x00,0x00,0x00,
        AfterTest=	0x03,0xAE,0x01,0x0C,0x00,0x00,0x00,0x00,
        AfterTest=	0x02,0xAA,0x00,0x00,0x00,0x00,0x00,0x00,
        [end]
    """.trimIndent()

    private val taggedMbfText = """
        ; synthetic
        ##MB01=Synthetic List
        [begin]
        MEASDATA=03,10,11
        DISABLE_ALL
        ENABLE_RANGE=0001-0002
        [end]

        [MEASURING BLOCK DATA]
        Supply Voltage,string,[V]
        Pump Relay,string,Off,On,**PUMP**
    """.trimIndent()

    @Test
    fun `a running test shows live display-tag readouts`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY", secondaryId = 0x540)),
            listOf(
                outputTestsFile("RECKEY", taggedScriptText),
                measuringBlocksFile("RECKEY", taggedMbfText),
            ),
        )
        val transport = scriptedTransport(backgroundScope)
        // The script's 4-significant-byte AA record -> single-frame PCI 0x04.
        val scheduleFrame = frame(0x240, 0x04, 0xAA, 0x03, 0x10, 0x11)
        transport.onFrame(scheduleFrame).respondWith(
            CanFrame(0x540, bytes(0x10, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestStart(0)
        viewModel.confirmStart()
        settle()

        val running = viewModel.state.value as OutputTestsUiState.Running
        assertEquals(1, running.readouts.size)
        assertEquals("Pump Relay", running.readouts[0].binding.row.label)
        // DPID 0x10 byte 1 = 0x01 -> the "On" state label.
        assertEquals("On", running.readouts[0].display)

        viewModel.stop()
        settle()
    }
}
