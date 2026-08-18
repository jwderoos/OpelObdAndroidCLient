package nl.jwdr.ooc.catalog

import java.nio.charset.Charset

/** Text handling for decoded catalog files (single-byte code page, not UTF-8). */
object CatalogText {

    private val windows1252: Charset = Charset.forName("windows-1252")

    /** Decodes raw catalog file bytes (Windows-1252, per docs/catalog-format.md). */
    fun decode(bytes: ByteArray): String = String(bytes, windows1252)

    /**
     * Splits catalog text into lines with 1-based numbers, accepting CR LF and
     * LF, and dropping `;` comments and blank lines. Trailing spaces and
     * line-terminator remnants are trimmed, but a trailing TAB is preserved:
     * it delimits an empty trailing field (they occur in real catalogs; see
     * docs/catalog-format.md). Real files also end with stray NUL bytes after
     * the final CRLF; NULs count as blank, so that junk never reaches a
     * parser.
     */
    internal fun contentLines(text: String): List<Line> =
        text.lineSequence()
            .mapIndexed { index, raw -> Line(index + 1, raw.trimEnd('\r', '\n', ' ')) }
            .filter { line ->
                line.text.any { !it.isWhitespace() && it != '\u0000' } && !line.text.startsWith(";")
            }
            .toList()

    internal data class Line(val number: Int, val text: String)
}
