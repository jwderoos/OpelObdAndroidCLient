package nl.jwdr.ooc.catalogstore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import nl.jwdr.ooc.catalog.CatalogTree

/**
 * A decoded catalog folder picked with ACTION_OPEN_DOCUMENT_TREE.
 *
 * Directory listings are cached: `DocumentFile.findFile` re-queries the whole
 * directory over the DocumentsProvider for every lookup, which makes per-file
 * resolution O(n²) across an import of hundreds of files (observed as a
 * multi-hour "hang" on a real catalog). With the cache each directory is
 * queried exactly once.
 */
class SafCatalogTree(private val context: Context, treeUri: Uri) : CatalogTree {

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)
    private val childrenByDirectory = mutableMapOf<String, Map<String, DocumentFile>>()

    override fun list(directory: String): List<String> =
        children(directory).values.filter { it.isFile }.mapNotNull { it.name }

    override fun read(path: String): ByteArray? {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val file = children(directory)[name]?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    private fun children(directory: String): Map<String, DocumentFile> =
        childrenByDirectory.getOrPut(directory) {
            directoryDocument(directory)?.listFiles().orEmpty()
                .mapNotNull { child -> child.name?.let { it to child } }
                .toMap()
        }

    private fun directoryDocument(directory: String): DocumentFile? {
        if (directory.isEmpty()) return root
        val parent = directory.substringBeforeLast('/', missingDelimiterValue = "")
        val name = directory.substringAfterLast('/')
        return children(parent)[name]?.takeIf { it.isDirectory }
    }
}

/** A single picked `opeldata.txt`, exposed as a one-file catalog tree. */
class SafSingleFileTree(private val context: Context, private val uri: Uri) : CatalogTree {

    override fun list(directory: String): List<String> =
        if (directory.isEmpty()) listOf("opeldata.txt") else emptyList()

    override fun read(path: String): ByteArray? {
        if (path != "opeldata.txt") return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}
