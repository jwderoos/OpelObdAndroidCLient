package nl.jwdr.ooc.ui.livedata

import java.io.File

/** Persists CSV logs under the app-private `files/livedata/` directory. */
class FileLiveDataCsvStore(private val directory: File) : LiveDataCsvStore {

    override fun save(fileName: String, content: String): String {
        directory.mkdirs()
        val file = File(directory, fileName)
        file.writeText(content)
        return file.absolutePath
    }
}
