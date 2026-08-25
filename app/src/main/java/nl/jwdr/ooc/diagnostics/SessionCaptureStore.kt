package nl.jwdr.ooc.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.CanLogSink
import nl.jwdr.ooc.transport.LoggedFrame

/**
 * On-device capture of one adapter session for after-the-fact analysis
 * (issue #29): a `session.canlog` of decoded frames (replayable with
 * `ReplayTransport`) and a `usb.trace` of raw link-level events, side by side
 * in `<directory>/<yyyyMMdd-HHmmss>/`.
 *
 * [openSession] is the `RecordingTransport` sink factory; [trace] is handed to
 * the USB link and is a no-op while no session is open, so the link needs no
 * knowledge of the debug toggle. Every line is flushed immediately: a crash
 * or a yanked dongle must not lose the frames that led up to it.
 */
class SessionCaptureStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var current: Session? = null

    private inner class Session(dir: File, val startMs: Long) : CanLogSink {
        private val canlog: BufferedWriter = File(dir, CANLOG_FILE).bufferedWriter()
        val usbTrace: BufferedWriter = File(dir, USB_TRACE_FILE).bufferedWriter()

        fun header(metadata: Map<String, String>) {
            canlog.write(CanLog.HEADER)
            canlog.newLine()
            for ((key, value) in metadata) {
                canlog.write("# $key: $value")
                canlog.newLine()
            }
            canlog.flush()
        }

        override fun frame(frame: LoggedFrame) = synchronized(lock) {
            canlog.write(CanLog.formatFrameLine(frame))
            canlog.newLine()
            canlog.flush()
        }

        override fun event(timestampMs: Long, text: String) = synchronized(lock) {
            canlog.write("# event $timestampMs: $text")
            canlog.newLine()
            canlog.flush()
        }

        override fun close() = synchronized(lock) {
            runCatching { canlog.close() }
            runCatching { usbTrace.close() }
            if (current === this) current = null
        }
    }

    /** Starts a new capture directory; closes a still-open previous session first. */
    fun openSession(metadata: Map<String, String>): CanLogSink = synchronized(lock) {
        current?.close()
        val startMs = clock()
        val dir = File(directory, DIR_FORMAT.format(Date(startMs))).also { it.mkdirs() }
        Session(dir, startMs).also {
            it.header(metadata)
            current = it
        }
    }

    /** Appends one raw link event (`<ms since session start> <line>`) to the open session's `usb.trace`. */
    fun trace(line: String) = synchronized(lock) {
        val session = current ?: return
        session.usbTrace.write("${clock() - session.startMs} $line")
        session.usbTrace.newLine()
        session.usbTrace.flush()
    }

    fun latestSession(): File? =
        directory.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }

    /** Bundles [latestSession] into `<directory>/<session>.zip` for the share sheet. */
    fun zipLatest(): File? {
        val session = latestSession() ?: return null
        val zip = File(directory, "${session.name}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for (file in session.listFiles()!!.sortedBy { it.name }) {
                out.putNextEntry(ZipEntry("${session.name}/${file.name}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    private companion object {
        const val CANLOG_FILE = "session.canlog"
        const val USB_TRACE_FILE = "usb.trace"
        val DIR_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    }
}
