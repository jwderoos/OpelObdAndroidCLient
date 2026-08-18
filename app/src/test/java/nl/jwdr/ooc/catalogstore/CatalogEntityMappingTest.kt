package nl.jwdr.ooc.catalogstore

import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.catalog.CatalogFile
import nl.jwdr.ooc.catalog.CatalogFileKind
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalog.ImportedCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private val canEcu = EcuDefinition(
    modelYear = "2010 (A)",
    vehicle = "Examplia-A",
    group = "Engine",
    name = "Z 99 XX",
    systemName = "Motronic X",
    protocol = "CAN",
    address = EcuAddress.Can(CanBus.HSCAN, 5000, 0x7E0, 0x5E8, 0x7E8),
    catalogKey = "EXAMPLIAENGZ99XX",
)

private val klineEcu = canEcu.copy(
    protocol = "KW2000",
    address = EcuAddress.KLine(baudRate = 10400, address = 89, initType = 3, extra = 7),
    catalogKey = "EXAMPLIASRS",
)

private val pseudoEcu = canEcu.copy(
    address = EcuAddress.None,
    builtinFunction = "IDENT",
    catalogKey = null,
)

class CatalogEntityMappingTest {

    @Test
    fun `can ecu round-trips through its entity`() {
        assertEquals(canEcu, canEcu.toEntity(catalogId = 1).toDefinition())
    }

    @Test
    fun `k-line ecu round-trips through its entity`() {
        assertEquals(klineEcu, klineEcu.toEntity(catalogId = 1).toDefinition())
    }

    @Test
    fun `pseudo ecu round-trips through its entity`() {
        assertEquals(pseudoEcu, pseudoEcu.toEntity(catalogId = 1).toDefinition())
    }

    @Test
    fun `payload carries label hash date and rows`() {
        val imported = ImportedCatalog(
            ecuDefinitions = listOf(canEcu, klineEcu),
            files = listOf(
                CatalogFile(CatalogFileKind.ERROR_CODES, "EXAMPLIAENGZ99XX", "EXAMPLIAENGZ99XX.txt", byteArrayOf(1, 2)),
            ),
            sourceHash = "abc123",
        )
        val payload = imported.toPayload(label = "My Catalog", importedAtEpochMillis = 1000L)
        assertEquals(
            CatalogEntity(label = "My Catalog", sourceHash = "abc123", importedAtEpochMillis = 1000L),
            payload.catalog,
        )
        assertEquals(2, payload.ecus.size)
        assertEquals(CatalogEntity.SINGLETON_ID, payload.ecus[0].catalogId)
        val file = payload.files.single()
        assertEquals("ERROR_CODES", file.kind)
        assertEquals("EXAMPLIAENGZ99XX", file.fileKey)
        assertEquals("EXAMPLIAENGZ99XX.txt", file.fileName)
        assertArrayEquals(byteArrayOf(1, 2), file.content)
    }
}
