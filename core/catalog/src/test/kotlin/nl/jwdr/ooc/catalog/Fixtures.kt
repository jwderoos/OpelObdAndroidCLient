package nl.jwdr.ooc.catalog

/** Loads a synthetic fixture file and decodes it like the app would. */
fun fixture(path: String): String {
    val bytes = checkNotNull(
        object {}.javaClass.getResourceAsStream("/synthetic-catalog/$path")
    ) { "missing fixture $path" }.readBytes()
    return CatalogText.decode(bytes)
}
