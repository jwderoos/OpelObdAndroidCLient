package nl.jwdr.ooc.ui.ecus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.R
import nl.jwdr.ooc.catalogstore.CatalogEntity
import nl.jwdr.ooc.catalogstore.CatalogPayload
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuEntity
import nl.jwdr.ooc.catalogstore.FakeCatalogDao
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
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
class EcuListViewModelTest {

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
        EcuListViewModel(repository, DiagnosticsManager(transport))

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private fun canEcu(name: String, requestId: Int, secondaryId: Int = 0) = EcuEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        modelYear = "2005",
        vehicle = "Astra-H",
        groupName = "Body",
        name = name,
        systemName = "$name system",
        protocol = "CAN",
        builtinFunction = null,
        catalogKey = null,
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

    private suspend fun storeCatalog(vararg ecus: EcuEntity) {
        dao.replaceCatalog(
            CatalogPayload(
                catalog = CatalogEntity(label = "test", sourceHash = "h", importedAtEpochMillis = 1L),
                ecus = ecus.toList(),
                files = emptyList(),
            ),
        )
    }

    @Test
    fun `without a catalog the screen asks for an import`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(EcuListUiState.NoCatalog, viewModel.state.value)
    }

    /** Selects the fixture's only vehicle, year, and (implicitly, auto-skipped) ECU group. */
    private suspend fun selectAstraH2005(viewModel: EcuListViewModel) {
        viewModel.selectVehicleName("Astra-H")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectYear("2005")
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `with a catalog but no selection the screen offers the vehicle-name picker`() = runTest(dispatcher) {
        storeCatalog(canEcu("Engine", 0x7E0))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(EcuListUiState.PickVehicle(listOf("Astra-H")), viewModel.state.value)
    }

    @Test
    fun `selecting a vehicle name offers its model years`() = runTest(dispatcher) {
        storeCatalog(
            canEcu("Engine", 0x7E0),
            canEcu("Engine", 0x7E0).copy(modelYear = "2009"),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectVehicleName("Astra-H")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            EcuListUiState.PickYear("Astra-H", listOf("2005", "2009")),
            viewModel.state.value,
        )
    }

    @Test
    fun `back from the year picker returns to the vehicle-name picker`() = runTest(dispatcher) {
        storeCatalog(canEcu("Engine", 0x7E0))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectVehicleName("Astra-H")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.backToVehicleNames()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(EcuListUiState.PickVehicle(listOf("Astra-H")), viewModel.state.value)
    }

    @Test
    fun `selecting a year with more than one ECU group offers the group picker`() = runTest(dispatcher) {
        storeCatalog(
            canEcu("Engine", 0x7E0).copy(groupName = "Engine"),
            canEcu("ABS", 0x241).copy(groupName = "Chassis"),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        selectAstraH2005(viewModel)

        assertEquals(
            EcuListUiState.PickEcuGroup(VehicleRef("2005", "Astra-H"), listOf("Chassis", "Engine")),
            viewModel.state.value,
        )
    }

    @Test
    fun `back from the ECU-group picker returns to the year picker`() = runTest(dispatcher) {
        storeCatalog(
            canEcu("Engine", 0x7E0).copy(groupName = "Engine"),
            canEcu("ABS", 0x241).copy(groupName = "Chassis"),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.backToYearPicker()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            EcuListUiState.PickYear("Astra-H", listOf("2005")),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting an ECU group shows only that group's CAN ECUs, not yet scanned`() = runTest(dispatcher) {
        storeCatalog(
            canEcu("Engine", 0x7E0).copy(groupName = "Engine"),
            canEcu("ABS", 0x241).copy(groupName = "Chassis"),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.selectGroup("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as EcuListUiState.Ecus
        assertEquals(VehicleRef("2005", "Astra-H"), state.vehicle)
        assertEquals("Engine", state.group)
        assertFalse(state.scanning)
        assertEquals(
            listOf(EcuRow("Engine", "Engine system", EcuRowStatus.NotScanned)),
            state.rows,
        )
    }

    @Test
    fun `a single ECU group is auto-selected, skipping straight to the ECU list`() = runTest(dispatcher) {
        storeCatalog(canEcu("ABS", 0x241), canEcu("Engine", 0x7E0))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        selectAstraH2005(viewModel)

        val state = viewModel.state.value as EcuListUiState.Ecus
        assertEquals(VehicleRef("2005", "Astra-H"), state.vehicle)
        assertEquals("Body", state.group)
        assertFalse(state.scanning)
        assertEquals(
            listOf(
                EcuRow("ABS", "ABS system", EcuRowStatus.NotScanned),
                EcuRow("Engine", "Engine system", EcuRowStatus.NotScanned),
            ),
            state.rows,
        )
    }

    @Test
    fun `a scan connects if needed and reports presence and fault status per ECU`() = runTest(dispatcher) {
        storeCatalog(canEcu("ABS", 0x241), canEcu("Engine", 0x7E0))
        val transport = FakeEcuTransport(backgroundScope)
        // ABS answers with one DTC; the engine address stays silent.
        transport.onFrame(frame(0x241, 0x04, 0x18, 0x02, 0xFF, 0x00))
            .respondWith(frame(0x249, 0x05, 0x58, 0x01, 0x01, 0x70, 0xE1))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.startScan()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Ready, transport.state.value)
        val state = viewModel.state.value as EcuListUiState.Ecus
        assertFalse(state.scanning)
        assertNull(state.error)
        assertEquals(
            listOf(
                EcuRow("ABS", "ABS system", EcuRowStatus.Present(dtcCount = 1)),
                EcuRow("Engine", "Engine system", EcuRowStatus.Absent),
            ),
            state.rows,
        )
    }

    @Test
    fun `a scan reports a GMLAN ECU's DTC count via readDiagnosticInformation`() = runTest(dispatcher) {
        // GMLAN response id is requestId + 0x400 (real addressing), not the
        // canEcu() default of +8 (that formula fits this file's KWP2000 tests).
        storeCatalog(canEcu("AHL", 0x249, secondaryId = 0x549).copy(responseId = 0x649))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x249, 0x01, 0x20)).respondWith(frame(0x649, 0x01, 0x60))
        transport.onFrame(frame(0x249, 0x03, 0xA9, 0x81, 0x12)).respondWith(
            frame(0x549, 0x81, 0x93, 0x25, 0x03, 0x92),
            frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.startScan()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as EcuListUiState.Ecus
        assertEquals(
            listOf(EcuRow("AHL", "AHL system", EcuRowStatus.Present(dtcCount = 1))),
            state.rows,
        )
    }

    @Test
    fun `a scan failure surfaces a user-readable error and stops scanning`() = runTest(dispatcher) {
        storeCatalog(canEcu("Engine", 0x7E0))
        val broken = object : ObdTransport {
            override val state: StateFlow<ConnectionState> =
                MutableStateFlow(ConnectionState.Ready)
            override val incomingFrames: Flow<CanFrame> = emptyFlow()
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(frame: CanFrame) =
                throw IllegalStateException("bus gone")
        }
        val viewModel = viewModel(broken)
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.startScan()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as EcuListUiState.Ecus
        assertFalse(state.scanning)
        assertEquals(R.string.error_transport_lost, state.error!!.resId)
    }

    @Test
    fun `changing the vehicle returns to the vehicle-name picker`() = runTest(dispatcher) {
        storeCatalog(canEcu("Engine", 0x7E0))
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.changeVehicle()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is EcuListUiState.PickVehicle)
    }
}
