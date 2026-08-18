package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryMeasuringBlocksTest {

    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private fun measuringBlocksFile(fileKey: String, fileName: String, text: String) =
        CatalogFileEntity(
            catalogId = CatalogEntity.SINGLETON_ID,
            kind = "MEASURING_BLOCKS",
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
    fun `parses the stored measuring-block file of a catalog key`() = runTest {
        storeFiles(
            measuringBlocksFile(
                "ENGINE", "ENGINE.MBF.txt",
                """
                ##MB01=Data List 1
                [begin]
                MEASDATA=04,
                DISABLE_ALL
                ENABLE_RANGE=0001-0001
                [end]

                [MEASURING BLOCK DATA]
                Coolant Temperature,string,[°C]
                """.trimIndent(),
            ),
        )

        val catalog = repository.measuringBlocksFor("ENGINE")!!

        assertEquals(1, catalog.blocks.size)
        assertEquals("Data List 1", catalog.blocks[0].title)
        assertEquals(listOf(0x04), catalog.blocks[0].measData)
        assertEquals("Coolant Temperature", catalog.rowsFor(catalog.blocks[0])[0].label)
    }

    @Test
    fun `a key without measuring-block files has no catalog`() = runTest {
        storeFiles()

        assertNull(repository.measuringBlocksFor("ENGINE"))
    }
}
