package nl.jwdr.ooc.transport

/** Direction of a logged frame, from the tester's point of view. */
enum class Direction { TX, RX }

/** One frame of a recorded session, [timestampMs] milliseconds after session start. */
data class LoggedFrame(
    val timestampMs: Long,
    val direction: Direction,
    val frame: CanFrame,
)

/** Error in an ooc-canlog document, pointing at the 1-based [lineNumber]. */
class CanLogParseException(message: String, val lineNumber: Int) :
    Exception("line $lineNumber: $message")

/**
 * A recorded CAN session in the ooc-canlog v1 format.
 *
 * The format is line-based UTF-8 text (see `docs/formats/canlog.md`):
 *
 * ```
 * # ooc-canlog v1
 * # vehicle: Astra H
 * 0   tx 246 01 A0
 * 12  rx 646 05 E0 A0 01 02
 * ```
 *
 * The first line must be the version header. Further `# key: value` comments
 * are collected into [metadata]; other comments and blank lines are ignored.
 * Frame lines are `<t_ms> <tx|rx> <id_hex> <payload hex bytes...>` with
 * non-decreasing timestamps.
 */
data class CanLog(
    val metadata: Map<String, String>,
    val frames: List<LoggedFrame>,
) {
    /** Serializes this log back to ooc-canlog v1 text; [parse] round-trips it. */
    fun format(): String = buildString {
        appendLine(HEADER)
        for ((key, value) in metadata) appendLine("# $key: $value")
        for (frame in frames) appendLine(formatFrameLine(frame))
    }

    companion object {
        const val HEADER = "# ooc-canlog v1"

        /** One `<t_ms> <tx|rx> <id_hex> <bytes...>` line without trailing newline. */
        fun formatFrameLine(logged: LoggedFrame): String = buildString {
            val (timestampMs, direction, frame) = logged
            append(timestampMs)
            append(' ').append(direction.name.lowercase())
            append(' ').append(frame.id.toString(16))
            for (byte in frame.data) append(' ').append("%02X".format(byte))
        }
        private val METADATA = Regex("""#\s*([^:\s]+):\s*(.*\S)\s*""")

        fun parse(text: String): CanLog {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != HEADER) {
                throw CanLogParseException("expected header \"$HEADER\"", 1)
            }
            val metadata = mutableMapOf<String, String>()
            val frames = mutableListOf<LoggedFrame>()
            for ((index, raw) in lines.withIndex().drop(1)) {
                val lineNumber = index + 1
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#") ->
                        METADATA.matchEntire(line)?.let { metadata[it.groupValues[1]] = it.groupValues[2] }
                    else -> {
                        val frame = parseFrameLine(line, lineNumber)
                        frames.lastOrNull()?.let {
                            if (frame.timestampMs < it.timestampMs) {
                                throw CanLogParseException(
                                    "timestamp ${frame.timestampMs} is before previous ${it.timestampMs}",
                                    lineNumber,
                                )
                            }
                        }
                        frames += frame
                    }
                }
            }
            return CanLog(metadata, frames)
        }

        private fun parseFrameLine(line: String, lineNumber: Int): LoggedFrame {
            val fields = line.split(Regex("""\s+"""))
            if (fields.size < 3) {
                throw CanLogParseException("expected \"<t_ms> <tx|rx> <id_hex> <payload...>\"", lineNumber)
            }
            val timestampMs = fields[0].toLongOrNull()
                ?: throw CanLogParseException("invalid timestamp \"${fields[0]}\"", lineNumber)
            val direction = when (fields[1]) {
                "tx" -> Direction.TX
                "rx" -> Direction.RX
                else -> throw CanLogParseException("invalid direction \"${fields[1]}\", expected tx or rx", lineNumber)
            }
            val id = fields[2].toIntOrNull(16)
                ?: throw CanLogParseException("invalid CAN id \"${fields[2]}\"", lineNumber)
            val data = ByteArray(fields.size - 3) { i ->
                val field = fields[i + 3]
                field.toIntOrNull(16)?.takeIf { field.length <= 2 }?.toByte()
                    ?: throw CanLogParseException("invalid payload byte \"$field\"", lineNumber)
            }
            val frame = try {
                CanFrame(id, data)
            } catch (e: IllegalArgumentException) {
                throw CanLogParseException(e.message ?: "invalid frame", lineNumber)
            }
            return LoggedFrame(timestampMs, direction, frame)
        }
    }
}
