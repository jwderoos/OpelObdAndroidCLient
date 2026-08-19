package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.OutputTestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryOutputTestsTest {

    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private fun outputTestsFile(fileKey: String, fileName: String, text: String) =
        CatalogFileEntity(
            catalogId = CatalogEntity.SINGLETON_ID,
            kind = "OUTPUT_TESTS",
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
    fun `parses the stored output-test file of a catalog key`() = runTest {
        storeFiles(
            outputTestsFile(
                "ENGINE", "ENGINE.SCR.txt",
                """
                ;KW2000
                Return Pump Relay Test
                [TESTTYPE=ONOFF]
                [begin]
                GoActivate=	0x04,0xAE,0x03,0x08,0x10,0x00,0x00,0x00,
                DeActivate=	0x04,0xAE,0x03,0x08,0x00,0x00,0x00,0x00,
                [end]
                """.trimIndent(),
            ),
        )

        val catalog = repository.outputTestsFor("ENGINE")!!

        assertEquals(1, catalog.tests.size)
        assertEquals("Return Pump Relay Test", catalog.tests[0].title)
        assertEquals(OutputTestType.ONOFF, catalog.tests[0].type)
        assertEquals(listOf(0xAE, 0x03, 0x08, 0x10), catalog.tests[0].goActivate[0].significantBytes)
    }

    @Test
    fun `a key without output-test files has no catalog`() = runTest {
        storeFiles()

        assertNull(repository.outputTestsFor("ENGINE"))
    }
}
