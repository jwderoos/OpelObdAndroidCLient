package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

private fun fixtureBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/synthetic-catalog/$path")).readBytes()

private fun syntheticTree(overrides: Map<String, ByteArray?> = emptyMap()): MapCatalogTree {
    val files = mutableMapOf(
        "opeldata.txt" to fixtureBytes("opeldata.txt"),
        "MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt" to fixtureBytes("MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt"),
        "ErrorCodes/EXAMPLIAENGZ99XX.txt" to fixtureBytes("ErrorCodes/EXAMPLIAENGZ99XX.txt"),
        "OutputTests/EXAMPLIAABSESP.SCR.txt" to fixtureBytes("OutputTests/EXAMPLIAABSESP.SCR.txt"),
        "CANVARCODING/EXAMPLIADIS.0x1201.txt" to fixtureBytes("CANVARCODING/EXAMPLIADIS.0x1201.txt"),
    )
    overrides.forEach { (path, bytes) -> if (bytes == null) files.remove(path) else files[path] = bytes }
    return MapCatalogTree(files)
}

class CatalogImporterTest {

    @Test
    fun `imports ecu definitions from opeldata`() {
        val imported = CatalogImporter.import(syntheticTree())
        assertEquals(7, imported.ecuDefinitions.size)
        assertTrue(imported.ecuDefinitions.any { it.catalogKey == "EXAMPLIAENGZ99XX" })
    }

    @Test
    fun `collects per-ecu files with kind and key`() {
        val files = CatalogImporter.import(syntheticTree()).files
        assertEquals(
            mapOf(
                CatalogFileKind.MEASURING_BLOCKS to "EXAMPLIAENGZ99XX",
                CatalogFileKind.ERROR_CODES to "EXAMPLIAENGZ99XX",
                CatalogFileKind.OUTPUT_TESTS to "EXAMPLIAABSESP",
                CatalogFileKind.CODING to "EXAMPLIADIS",
            ),
            files.associate { it.kind to it.key },
        )
        assertEquals(
            "EXAMPLIADIS.0x1201.txt",
            files.single { it.kind == CatalogFileKind.CODING }.fileName,
        )
    }

    @Test
    fun `hash is stable and content-sensitive`() {
        val first = CatalogImporter.import(syntheticTree()).sourceHash
        val second = CatalogImporter.import(syntheticTree()).sourceHash
        assertEquals(first, second)
        val changed = CatalogImporter.import(
            syntheticTree(mapOf("ErrorCodes/EXAMPLIAENGZ99XX.txt" to ";KWCAN\nP0100\n-00\tOther Text\n".toByteArray())),
        ).sourceHash
        assertNotEquals(first, changed)
    }

    @Test
    fun `fails without opeldata`() {
        try {
            CatalogImporter.import(syntheticTree(mapOf("opeldata.txt" to null)))
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertTrue(e.message!!.contains("opeldata.txt"))
        }
    }

    @Test
    fun `fails on corrupt per-ecu file naming the file`() {
        try {
            CatalogImporter.import(
                syntheticTree(mapOf("MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt" to "##MB01=Broken\n[begin]\n".toByteArray())),
            )
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("EXAMPLIAENGZ99XX.MBF.txt", e.fileName)
        }
    }

    @Test
    fun `ignores files outside the known directories`() {
        val imported = CatalogImporter.import(
            syntheticTree(mapOf("Programming/UNRELATED.txt" to byteArrayOf(1, 2, 3))),
        )
        assertEquals(4, imported.files.size)
    }
}
