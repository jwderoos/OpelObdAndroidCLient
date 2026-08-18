package nl.jwdr.ooc.catalogstore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import nl.jwdr.ooc.catalog.CatalogTree

/** A decoded catalog folder picked with ACTION_OPEN_DOCUMENT_TREE. */
class SafCatalogTree(private val context: Context, treeUri: Uri) : CatalogTree {

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override fun list(directory: String): List<String> =
        resolve(directory)?.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { it.name }
            .orEmpty()

    override fun read(path: String): ByteArray? {
        val file = resolve(path)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    private fun resolve(path: String): DocumentFile? =
        path.split('/').filter { it.isNotEmpty() }
            .fold(root) { dir, segment -> dir?.findFile(segment) }
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
