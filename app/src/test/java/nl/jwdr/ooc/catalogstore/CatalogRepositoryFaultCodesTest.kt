package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.FaultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryFaultCodesTest {

    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private fun errorCodesFile(fileKey: String, fileName: String, text: String) =
        CatalogFileEntity(
            catalogId = CatalogEntity.SINGLETON_ID,
            kind = "ERROR_CODES",
            fileKey = fileKey,
            fileName = fileName,
            content = text.toByteArray(Charsets.ISO_8859_1),
        )

    private suspend fun storeFiles(vararg files: CatalogFileEntity) {
        dao.replaceCatalog(
            CatalogPayload(
                catalog = CatalogEntity(label = "test", sourceHash = "h", importedAtEpochMillis = 1L),
                ecus = emptyList(),
                files = files.toList(),
            ),
        )
    }

    @Test
    fun `parses the stored fault-code file of a catalog key`() = runTest {
        storeFiles(
            errorCodesFile(
                "ENGINE", "ENGINE.txt",
                "[MB]\tENGINE\nP0016\n-00\tCrankshaft/Camshaft Correlation\n",
            ),
        )

        val catalog = repository.faultCodesFor("ENGINE")!!

        assertEquals(
            listOf(FaultCode("P0016", 0, "Crankshaft/Camshaft Correlation")),
            catalog.codes,
        )
    }

    @Test
    fun `merges suffixed variant files of the same key`() = runTest {
        storeFiles(
            errorCodesFile("ENGINE", "ENGINE.txt", "P0016\n-00\tBase Text\n"),
            errorCodesFile("ENGINE", "ENGINE_1.txt", "B1000\n-01\tVariant Text\n"),
        )

        val catalog = repository.faultCodesFor("ENGINE")!!

        assertEquals(
            listOf(
                FaultCode("P0016", 0, "Base Text"),
                FaultCode("B1000", 1, "Variant Text"),
            ),
            catalog.codes,
        )
    }

    @Test
    fun `a key without fault-code files has no catalog`() = runTest {
        storeFiles()

        assertNull(repository.faultCodesFor("ENGINE"))
    }
}
