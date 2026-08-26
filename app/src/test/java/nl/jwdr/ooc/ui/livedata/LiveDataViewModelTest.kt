package nl.jwdr.ooc.ui.livedata

import kotlin.time.Duration.Companion.milliseconds
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
import nl.jwdr.ooc.diagnostics.LiveDecodeRuleStore
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private val savedCsvs = mutableListOf<Pair<String, String>>()
    private val csvStore = LiveDataCsvStore { fileName, content ->
        savedCsvs += fileName to content
        "/data/livedata/$fileName"
    }

    private var nowMs = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(transport: ObdTransport) = LiveDataViewModel(
        repository,
        DiagnosticsManager(transport),
        csvStore,
        LiveDecodeRuleStore { "{}".byteInputStream() },
        clock = { nowMs },
    )

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame `AA <rate> <dpid>` schedule request for the ENG fixture. */
    private val scheduleRequest = frame(0x7E0, 0x03, 0xAA, 0x03, 0x04)

    /** One UUDT broadcast of the fixture's DPID 0x04 on the secondary id. */
    private fun engineBroadcast(temperature: Int, relay: Int = 0x01) =
        frame(0x5E8, 0x04, temperature, relay, 0x00, 0x00, 0x00, 0x00, 0x00)

    private fun canEcu(
        name: String,
        requestId: Int,
        catalogKey: String? = null,
        secondaryId: Int = requestId + 8 - 0x200,
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

    private fun measuringBlocksFile(fileKey: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "MEASURING_BLOCKS",
        fileKey = fileKey,
        fileName = "$fileKey.MBF.txt",
        content = """
            ##MB01=Data List 1
            [begin]
            MEASDATA=03,04
            DISABLE_ALL
            ENABLE_RANGE=0001-0002
            [end]

            [MEASURING BLOCK DATA]
            Coolant Temperature,string,[°C]
            Fuel Pump Relay,string,Inactive,Active
        """.trimIndent().toByteArray(Charsets.ISO_8859_1),
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

        assertEquals(LiveDataUiState.NoVehicle, viewModel.state.value)
    }

    @Test
    fun `the ECU picker lists only modules that have a measuring-block file`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("ABS", 0x241), // no catalogKey / no MBF -> no live data
                canEcu("Engine", 0x7E0, catalogKey = "ENG"),
            ),
            files = listOf(measuringBlocksFile("ENG")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            LiveDataUiState.PickEcu(listOf(EcuChoice("Engine", "Engine system"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting an ECU offers its measuring blocks`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(canEcu("Engine", 0x7E0, catalogKey = "ENG")),
            files = listOf(measuringBlocksFile("ENG")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            LiveDataUiState.PickBlock("Engine", listOf(BlockChoice(1, "Data List 1"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `an ECU without measuring blocks is not offered at all`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("Engine", 0x7E0)))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDataUiState.PickEcu(emptyList()), viewModel.state.value)
    }

    private suspend fun storeEngineWithBlocks() = storeCatalog(
        ecus = listOf(canEcu("Engine", 0x7E0, catalogKey = "ENG")),
        files = listOf(measuringBlocksFile("ENG")),
    )

    @Test
    fun `selecting a block polls it and shows decoded rows`() = runTest(dispatcher) {
        storeEngineWithBlocks()
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            0.milliseconds to engineBroadcast(0x50),
            600.milliseconds to engineBroadcast(0x51),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectBlock(1)
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()

        val first = viewModel.state.value as LiveDataUiState.Live
        assertEquals("Engine", first.ecuName)
        assertEquals("Data List 1", first.blockTitle)
        assertNull(first.error)
        assertEquals(listOf("80", "Active"), first.rows.map { it.display })

        dispatcher.scheduler.advanceTimeBy(600)
        dispatcher.scheduler.runCurrent()

        val second = viewModel.state.value as LiveDataUiState.Live
        assertEquals("81", second.rows[0].display)

        // Stop the endless poll so runTest's cleanup can reach idle.
        viewModel.changeBlock()
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `numeric rows accumulate chart samples`() = runTest(dispatcher) {
        storeEngineWithBlocks()
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(engineBroadcast(0x50))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectBlock(1)
        dispatcher.scheduler.advanceTimeBy(1200)
        dispatcher.scheduler.runCurrent()

        val state = viewModel.state.value as LiveDataUiState.Live
        assertTrue("expected multiple samples", state.rows[0].samples.size >= 2)
        assertEquals(80.0, state.rows[0].samples.first().value, 0.0)

        viewModel.changeBlock()
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a failing poll surfaces a user-readable error`() = runTest(dispatcher) {
        // No secondary CAN id: GMLAN live data cannot schedule periodic data.
        storeCatalog(
            ecus = listOf(canEcu("Engine", 0x7E0, catalogKey = "ENG", secondaryId = 0)),
            files = listOf(measuringBlocksFile("ENG")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectBlock(1)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as LiveDataUiState.Live
        assertFalse(state.polling)
        assertEquals(R.string.error_generic_communication, state.error!!.resId)
    }

    @Test
    fun `logging captures each reading and saving returns the CSV path`() = runTest(dispatcher) {
        storeEngineWithBlocks()
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(engineBroadcast(0x50))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectBlock(1)
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()

        nowMs = 1000
        viewModel.startLogging()
        dispatcher.scheduler.advanceTimeBy(1200)
        dispatcher.scheduler.runCurrent()
        viewModel.stopLogging()
        dispatcher.scheduler.runCurrent()

        val state = viewModel.state.value as LiveDataUiState.Live
        assertFalse(state.logging)
        assertEquals("/data/livedata/livedata-1000.csv", state.savedCsvPath)
        assertEquals(1, savedCsvs.size)
        val (fileName, content) = savedCsvs[0]
        assertEquals("livedata-1000.csv", fileName)
        val lines = content.trim().lines()
        assertEquals("timestamp_ms,ecu,block,label,value,unit", lines[0])
        assertTrue("expected logged data lines, got ${lines.size}", lines.size >= 3)
        assertTrue(lines[1].contains("Coolant Temperature"))

        viewModel.changeBlock()
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `changing the block stops polling and returns to the block picker`() = runTest(dispatcher) {
        storeEngineWithBlocks()
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(engineBroadcast(0x50))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectBlock(1)
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()

        viewModel.changeBlock()
        dispatcher.scheduler.runCurrent()
        val framesAfterStop = transport.sentFrames.size
        dispatcher.scheduler.advanceTimeBy(2000)
        dispatcher.scheduler.runCurrent()

        assertEquals(
            LiveDataUiState.PickBlock("Engine", listOf(BlockChoice(1, "Data List 1"))),
            viewModel.state.value,
        )
        assertEquals("polling must stop", framesAfterStop, transport.sentFrames.size)
    }

    // Generic OBD-II fallback (#14)

    private fun obd2Transport(scope: kotlinx.coroutines.CoroutineScope): FakeEcuTransport {
        val transport = FakeEcuTransport(scope)
        // Functional probe: one ECU answers.
        transport.onFrame(frame(0x7DF, 0x02, 0x01, 0x00))
            .respondWith(frame(0x7E8, 0x06, 0x41, 0x00, 0x08, 0x10, 0x00, 0x00))
        // Supported PIDs: coolant (0x05) and RPM (0x0C), no chaining.
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x00))
            .respondWith(frame(0x7E8, 0x06, 0x41, 0x00, 0x08, 0x10, 0x00, 0x00))
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x05))
            .respondWith(frame(0x7E8, 0x03, 0x41, 0x05, 0x5A))
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x0C))
            .respondWith(frame(0x7E8, 0x04, 0x41, 0x0C, 0x1D, 0xF8))
        return transport
    }

    @Test
    fun `without a vehicle the OBD-II fallback discovers ECUs`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(obd2Transport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LiveDataUiState.NoVehicle, viewModel.state.value)

        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            LiveDataUiState.PickEcu(listOf(EcuChoice("0x7E0", "OBD-II"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting an OBD-II ECU polls its supported PIDs`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(obd2Transport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("0x7E0")
        dispatcher.scheduler.advanceTimeBy(1000)
        dispatcher.scheduler.runCurrent()

        val state = viewModel.state.value as LiveDataUiState.Live
        assertEquals("0x7E0", state.ecuName)
        assertNull(state.error)
        assertEquals(
            listOf("Engine coolant temperature" to "50", "Engine speed" to "1918"),
            state.rows.map { it.label to it.display },
        )
        assertEquals("°C", state.rows[0].unit)
        assertTrue(state.rows[0].samples.isNotEmpty())

        viewModel.changeEcu()
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `OBD-II logging saves a CSV`() = runTest(dispatcher) {
        storeCatalog(listOf(canEcu("Engine", 0x7E0)), selected = false)
        val viewModel = viewModel(obd2Transport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.useObd2()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("0x7E0")
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()

        viewModel.startLogging()
        dispatcher.scheduler.advanceTimeBy(1200)
        dispatcher.scheduler.runCurrent()
        viewModel.stopLogging()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, savedCsvs.size)
        val lines = savedCsvs[0].second.trim().lines()
        assertEquals("timestamp_ms,ecu,block,label,value,unit", lines[0])
        assertTrue(lines[1].contains("Engine coolant temperature"))
        assertTrue(lines[1].contains("OBD-II"))

        viewModel.changeEcu()
        dispatcher.scheduler.runCurrent()
    }
}
