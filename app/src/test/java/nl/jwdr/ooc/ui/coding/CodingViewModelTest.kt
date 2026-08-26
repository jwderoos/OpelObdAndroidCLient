package nl.jwdr.ooc.ui.coding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.catalogstore.CatalogEntity
import nl.jwdr.ooc.catalogstore.CatalogFileEntity
import nl.jwdr.ooc.catalogstore.CatalogPayload
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuEntity
import nl.jwdr.ooc.catalogstore.FakeCatalogDao
import nl.jwdr.ooc.catalogstore.VehicleRef
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.ui.faultcodes.EcuChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodingViewModelTest {

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

    private fun viewModel(transport: ObdTransport, expertMode: Boolean = true) =
        CodingViewModel(repository, DiagnosticsManager(transport), MutableStateFlow(expertMode))

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

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

    private fun codingFile(fileKey: String, fileName: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "CODING",
        fileKey = fileKey,
        fileName = fileName,
        content = """
            ;Test
            [DID_begin]
            44,02
            [DID_end]

            [VARIANT CODING DATA]
            Row,string,A,B
        """.trimIndent().toByteArray(Charsets.ISO_8859_1),
    )

    private suspend fun storeCatalog(ecus: List<EcuEntity>, files: List<CatalogFileEntity> = emptyList()) {
        dao.replaceCatalog(
            CatalogPayload(
                catalog = CatalogEntity(
                    label = "test",
                    sourceHash = "h",
                    importedAtEpochMillis = 1L,
                    selectedModelYear = "2005",
                    selectedVehicle = "Astra-H",
                ),
                ecus = ecus,
                files = files,
            ),
        )
    }

    @Test
    fun `the ECU picker lists only modules with a coding file`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("ABS", 0x241), // no catalogKey / no coding file
                canEcu("UEC", 0x250, "UECKEY"),
            ),
            files = listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CodingUiState.PickEcu(listOf(EcuChoice("UEC", "UEC system"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `with more than one ECU group the screen offers the group picker`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("UEC", 0x250, "UECKEY").copy(groupName = "Engine"),
                canEcu("ABS", 0x241).copy(groupName = "Chassis"),
            ),
            files = listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CodingUiState.PickEcuGroup(VehicleRef("2005", "Astra-H"), listOf("Chassis", "Engine")),
            viewModel.state.value,
        )
    }

    @Test
    fun `selecting a group narrows the picker to that group's codable ECUs`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("UEC", 0x250, "UECKEY").copy(groupName = "Engine"),
                canEcu("ABS", 0x241).copy(groupName = "Chassis"),
            ),
            files = listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectGroup("Engine")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CodingUiState.PickEcu(listOf(EcuChoice("UEC", "UEC system"))),
            viewModel.state.value,
        )
    }

    @Test
    fun `changing the ECU re-offers the group picker when more than one group exists`() = runTest(dispatcher) {
        storeCatalog(
            ecus = listOf(
                canEcu("UEC", 0x250, "UECKEY").copy(groupName = "Engine"),
                canEcu("ABS", 0x241).copy(groupName = "Chassis"),
            ),
            files = listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectGroup("Engine")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.changeEcu()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CodingUiState.PickEcuGroup(VehicleRef("2005", "Astra-H"), listOf("Chassis", "Engine")),
            viewModel.state.value,
        )
    }

    @Test
    fun `an ECU with one coding table opens it directly, skipping the table picker`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as CodingUiState.Entries
        assertEquals("UEC", state.ecuName)
        assertEquals(listOf(0x44), state.entries.map { it.id })
        assertEquals("0102", state.entries[0].currentHex)
    }

    @Test
    fun `an ECU with multiple coding tables shows the table picker`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(
                codingFile("UECKEY", "UECKEY.0x1201.txt"),
                codingFile("UECKEY", "UECKEY.0x1202.txt"),
            ),
        )
        val viewModel = viewModel(FakeEcuTransport(backgroundScope))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as CodingUiState.PickTable
        assertEquals(setOf(0x1201, 0x1202), state.tables.map { it.dataIdentifier }.toSet())
    }

    @Test
    fun `editing then requesting a write with the wrong byte count reports an error`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.editEntry(0x44, "AA") // entry is 2 bytes; "AA" is only 1
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as CodingUiState.Entries
        assertNotNull(state.error)
        assertEquals(false, state.confirmingWrite)
    }

    @Test
    fun `a valid edit opens the confirmation dialog without touching the bus`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as CodingUiState.Entries
        assertTrue(state.confirmingWrite)
        assertTrue("nothing on the bus before confirmation", transport.sentFrames.none { it.data[1] == 0x3B.toByte() })
    }

    @Test
    fun `confirming a write updates the entry and clears the edit`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        // The same "1A 44" request fires twice: once for the initial read
        // (selectEcu), once for writeCoding's post-write verification
        // re-read. onFrame rules aren't single-use, so a stateful respondBy
        // is needed to return the old value first, then the newly-written one.
        var reads = 0
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44)).respondBy {
            reads++
            listOf(if (reads == 1) frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02) else frame(0x258, 0x04, 0x5A, 0x44, 0xAA, 0xBB))
        }
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x44, 0xAA, 0xBB))
            .respondWith(frame(0x258, 0x02, 0x7B, 0x44))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmWrite()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as CodingUiState.Entries
        assertEquals("AABB", state.entries[0].currentHex)
        assertEquals(null, state.entries[0].editedHex)
        assertEquals(false, state.confirmingWrite)
    }

    /**
     * Scripts one 2-byte entry whose write ack only arrives after 200 ms, so a
     * teardown can be injected while the batch is suspended mid-record.
     */
    private fun slowWriteTransport(scope: CoroutineScope): FakeEcuTransport {
        val transport = FakeEcuTransport(scope)
        // The same "1A 44" request fires twice: once for the initial read
        // (selectEcu), once for writeCoding's post-write verification re-read.
        var reads = 0
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44)).respondBy {
            reads++
            listOf(if (reads == 1) frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02) else frame(0x258, 0x04, 0x5A, 0x44, 0xAA, 0xBB))
        }
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x44, 0xAA, 0xBB))
            .respondWith(listOf(frame(0x258, 0x02, 0x7B, 0x44)), 200.milliseconds)
        return transport
    }

    /** Reads sent so far: 1 after the initial read, 2 once a write batch finished verifying. */
    private fun readCount(transport: FakeEcuTransport) =
        transport.sentFrames.count { it.data[1] == 0x1A.toByte() }

    @Test
    fun `an unrelated catalog re-emit does not tear down an in-flight write`() = runTest(dispatcher) {
        val ecus = listOf(canEcu("UEC", 0x250, "UECKEY"))
        storeCatalog(ecus, listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")))
        val transport = slowWriteTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmWrite()
        dispatcher.scheduler.advanceTimeBy(100)
        assertTrue("write request is on the bus", transport.sentFrames.any { it.data[1] == 0x3B.toByte() })

        // Room re-emits its observed flows on any catalog-table write; the
        // fresh entities are unequal payloads but an equal summary/selection.
        storeCatalog(ecus, listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")))
        dispatcher.scheduler.advanceTimeBy(50)

        val midWrite = viewModel.state.value as? CodingUiState.Entries
        assertTrue("the write must survive an unrelated re-emit", midWrite?.writing == true)

        dispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.state.value as CodingUiState.Entries
        assertEquals("AABB", state.entries[0].currentHex)
        assertEquals(false, state.writing)
    }

    @Test
    fun `clearing the ViewModel mid-write still finishes the batch`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = slowWriteTransport(backgroundScope)
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(transport) as T
            },
        )[CodingViewModel::class.java]
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmWrite()
        dispatcher.scheduler.advanceTimeBy(100) // 0x3B is out, ack still pending
        assertEquals("precondition: only the initial read so far", 1, readCount(transport))

        // Popping the screen off the back stack clears the store, which cancels
        // viewModelScope — the write batch must not die half-applied.
        store.clear()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("the post-write verification re-read must still run", 2, readCount(transport))
    }

    @Test
    fun `a vehicle change mid-write finishes the batch without resurrecting the screen`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = slowWriteTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmWrite()
        dispatcher.scheduler.advanceTimeBy(100)
        assertEquals("precondition: only the initial read so far", 1, readCount(transport))

        // A real selection change (unlike an equal re-emit) does reset the
        // screen — but only the reporting, never the batch.
        repository.selectVehicle(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("the post-write verification re-read must still run", 2, readCount(transport))
        assertEquals(CodingUiState.NoVehicle, viewModel.state.value)
    }

    @Test
    fun `editing a row clears its previous write outcome`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = slowWriteTransport(backgroundScope)
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmWrite()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(
            "precondition: the finished write tagged the row",
            (viewModel.state.value as CodingUiState.Entries).entries[0].outcome,
        )

        viewModel.editEntry(0x44, "CCDD")

        val state = viewModel.state.value as CodingUiState.Entries
        assertEquals(null, state.entries[0].outcome)
    }

    @Test
    fun `editing a row clears a stale validation error`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AA")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull((viewModel.state.value as CodingUiState.Entries).error)

        viewModel.editEntry(0x44, "AABB")

        assertEquals(null, (viewModel.state.value as CodingUiState.Entries).error)
    }

    @Test
    fun `a write is refused when expert mode is off`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("UEC", 0x250, "UECKEY")),
            listOf(codingFile("UECKEY", "UECKEY.0x1201.txt")),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x258, 0x04, 0x5A, 0x44, 0x01, 0x02))
        val viewModel = viewModel(transport, expertMode = false)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("UEC")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.editEntry(0x44, "AABB")
        viewModel.requestWrite()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmWrite()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("no write frame must reach the bus", transport.sentFrames.none { it.data[1] == 0x3B.toByte() })
        val state = viewModel.state.value as CodingUiState.Entries
        assertNotNull(state.error)
    }
}
