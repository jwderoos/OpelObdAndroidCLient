package nl.jwdr.ooc.catalog

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the full import against a real decoded catalog on this machine
 * (no-vendor-data policy: the catalog itself is never committed). Point
 * `OOC_CATALOG_DIR` at a decoded catalog folder (the one holding
 * `opeldata.txt`); when unset the suite skips cleanly, like the recorded-log
 * conformance tests:
 *
 * ```
 * OOC_CATALOG_DIR="$HOME/path/to/decoded/EN" ./gradlew :core:catalog:test \
 *   --tests "nl.jwdr.ooc.catalog.LocalCatalogConformanceTest"
 * ```
 */
class LocalCatalogConformanceTest {

    private class FileCatalogTree(private val root: File) : CatalogTree {
        override fun list(directory: String): List<String> =
            File(root, directory).listFiles()?.map { it.name }?.sorted().orEmpty()

        override fun read(path: String): ByteArray? =
            File(root, path).takeIf { it.isFile }?.readBytes()
    }

    @Test
    fun `a real decoded catalog imports without errors`() {
        val dir = System.getenv("OOC_CATALOG_DIR")?.let(::File)
        assumeTrue(
            "OOC_CATALOG_DIR not set or has no opeldata.txt (clean-room skip)",
            dir != null && File(dir, "opeldata.txt").isFile,
        )

        val imported = CatalogImporter.import(FileCatalogTree(dir!!))

        assertTrue("expected ECU definitions, found none", imported.ecuDefinitions.isNotEmpty())
        assertTrue("expected per-ECU files, found none", imported.files.isNotEmpty())
    }
}
