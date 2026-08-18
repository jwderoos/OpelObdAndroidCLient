package nl.jwdr.ooc.ui.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.catalog.CatalogTree
import nl.jwdr.ooc.catalogstore.CatalogDao
import nl.jwdr.ooc.catalogstore.CatalogEntity
import nl.jwdr.ooc.catalogstore.CatalogFileEntity
import nl.jwdr.ooc.catalogstore.CatalogPayload
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.EcuEntity
import nl.jwdr.ooc.catalogstore.FakeCatalogDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MapCatalogTree(private val files: Map<String, ByteArray>) : CatalogTree {
    override fun list(directory: String): List<String> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        return files.keys
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { it.removePrefix(prefix) }
    }
    override fun read(path: String): ByteArray? = files[path]
}

private val validTree = MapCatalogTree(
    mapOf("opeldata.txt" to "2010 (A)\tExamplia-A\tEngine\tZ 99 XX\tMotronic X\tCAN\tHSCAN\t0500.0\t0x000007E0\t0x000005E8\t0x000007E8\tEXAMPLIAENGZ99XX\n".toByteArray()),
)

private val treeWithEcuFiles = MapCatalogTree(
    mapOf(
        "opeldata.txt" to "2010 (A)\tExamplia-A\tEngine\tZ 99 XX\tMotronic X\tCAN\tHSCAN\t0500.0\t0x000007E0\t0x000005E8\t0x000007E8\tEXAMPLIAENGZ99XX\n".toByteArray(),
        "ErrorCodes/EXAMPLIAENGZ99XX.txt" to "P0016\n-00\tCrankshaft Correlation\n".toByteArray(),
        "ErrorCodes/EXAMPLIAABS.txt" to "C0040\n-00\tWheel Speed Sensor\n".toByteArray(),
    ),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeCatalogDao()
    private lateinit var viewModel: CatalogViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = CatalogViewModel(
            repository = CatalogRepository(dao, clock = { 1234L }),
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful import stores catalog and shows summary`() = runTest(dispatcher) {
        viewModel.import(validTree, label = "Test Catalog")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.importing)
        assertNull(state.errorMessage)
        assertNotNull(state.summary)
        assertEquals("Test Catalog", state.summary!!.label)
        assertEquals(1234L, state.summary!!.importedAtEpochMillis)
        assertEquals(1, state.summary!!.ecuCount)
        assertEquals(dao.stored.value!!.catalog.sourceHash, state.summary!!.sourceHash)
    }

    @Test
    fun `import reports per-file progress with the current file name`() = runTest(dispatcher) {
        viewModel.import(treeWithEcuFiles, label = "Test Catalog")
        dispatcher.scheduler.advanceUntilIdle()

        // Progress is kept after completion (the UI only shows it while
        // importing); the last event covers the full file count.
        val progress = viewModel.progress.value
        assertNotNull(progress)
        assertEquals(2, progress!!.done)
        assertEquals(2, progress.total)
        assertEquals("ErrorCodes/EXAMPLIAENGZ99XX.txt", progress.path)
    }

    @Test
    fun `failed import surfaces user-presentable message and keeps previous state`() = runTest(dispatcher) {
        viewModel.import(MapCatalogTree(emptyMap()), label = "Broken")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.importing)
        assertNull(state.summary)
        assertTrue(
            "message should name opeldata.txt: ${state.errorMessage}",
            state.errorMessage!!.contains("opeldata.txt"),
        )
        assertNull(dao.stored.value)
    }

    @Test
    fun `new import attempt clears previous error`() = runTest(dispatcher) {
        viewModel.import(MapCatalogTree(emptyMap()), label = "Broken")
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.state.value.errorMessage)

        viewModel.import(validTree, label = "Fixed")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.state.value.errorMessage)
        assertEquals("Fixed", viewModel.state.value.summary!!.label)
    }
}
