package nl.jwdr.ooc.catalog

/**
 * A catalog file could not be parsed. [message] is user-presentable: it names
 * the file, the line and what was expected, without leaking internals.
 */
class CatalogFormatException(
    val fileName: String,
    val lineNumber: Int?,
    val problem: String,
) : Exception(
    if (lineNumber != null) "$fileName line $lineNumber: $problem"
    else "$fileName: $problem"
)
