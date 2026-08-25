package nl.jwdr.ooc.transport

/**
 * Destination for one recorded session in the ooc-canlog v1 format.
 *
 * Opened by [RecordingTransport] per connect, closed on disconnect. The
 * transport module ships only [AppendableCanLogSink]; file-backed sinks live in
 * the app, which owns storage and the debug toggle.
 */
interface CanLogSink {
    fun frame(frame: LoggedFrame)

    /** Free-form annotation (`# event <t_ms>: text`), ignored by [CanLog.parse]. */
    fun event(timestampMs: Long, text: String)

    fun close()
}

/** [CanLogSink] that streams canlog text to [out], one complete line per call. */
class AppendableCanLogSink(
    private val out: Appendable,
    metadata: Map<String, String> = emptyMap(),
) : CanLogSink {
    init {
        out.append(CanLog.HEADER).append('\n')
        for ((key, value) in metadata) out.append("# ").append(key).append(": ").append(value).append('\n')
    }

    override fun frame(frame: LoggedFrame) {
        out.append(CanLog.formatFrameLine(frame)).append('\n')
    }

    override fun event(timestampMs: Long, text: String) {
        out.append("# event ").append(timestampMs.toString()).append(": ").append(text).append('\n')
    }

    override fun close() {}
}
