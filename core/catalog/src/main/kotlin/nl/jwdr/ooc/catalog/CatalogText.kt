package nl.jwdr.ooc.catalog

import java.nio.charset.Charset

/** Text handling for decoded catalog files (single-byte code page, not UTF-8). */
object CatalogText {

    private val windows1252: Charset = Charset.forName("windows-1252")

    /** Decodes raw catalog file bytes (Windows-1252, per docs/catalog-format.md). */
    fun decode(bytes: ByteArray): String = String(bytes, windows1252)

    /**
     * Splits catalog text into lines with 1-based numbers, accepting CR LF and
     * LF, trimming trailing whitespace, and dropping `;` comments and blanks.
     */
    internal fun contentLines(text: String): List<Line> =
        text.lineSequence()
            .mapIndexed { index, raw -> Line(index + 1, raw.trimEnd()) }
            .filter { it.text.isNotEmpty() && !it.text.startsWith(";") }
            .toList()

    internal data class Line(val number: Int, val text: String)
}
