package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryCodingTest {

    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private fun codingFile(fileKey: String, fileName: String, text: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "CODING",
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

    private val tableText = """
        ;Test
        [DID_begin]
        44,02
        [DID_end]

        [VARIANT CODING DATA]
        Row,string,A,B
    """.trimIndent()

    @Test
    fun `parses every coding file of a catalog key`() = runTest {
        storeFiles(
            codingFile("UEC", "UEC.0x1201.txt", tableText),
            codingFile("UEC", "UEC.0x1202.txt", tableText),
        )

        val tables = repository.codingTablesFor("UEC")

        assertEquals(listOf(0x1201, 0x1202), tables.map { it.dataIdentifier }.sorted())
    }

    @Test
    fun `a key without coding files has no tables`() = runTest {
        storeFiles()

        assertTrue(repository.codingTablesFor("UEC").isEmpty())
    }

    @Test
    fun `codingTableKeys lists only keys with a coding file`() = runTest {
        storeFiles(codingFile("UEC", "UEC.0x1201.txt", tableText))

        assertEquals(setOf("UEC"), repository.codingTableKeys())
    }
}
