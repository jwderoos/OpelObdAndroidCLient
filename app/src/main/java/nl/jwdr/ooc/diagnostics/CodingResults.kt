package nl.jwdr.ooc.diagnostics

/** One entry's raw record, as read from or written to an ECU by [DiagnosticsManager.readCoding]/[DiagnosticsManager.writeCoding]. */
data class CodingEntryRead(val id: Int, val bytes: ByteArray)

/** Every entry of one coding table, as read from the ECU. */
data class CodingReadResult(val entries: List<CodingEntryRead>)

/** Per-entry outcome of one [DiagnosticsManager.writeCoding] call, for every id present in its `edits` map. */
sealed interface CodingEntryOutcome {
    val id: Int

    /** The write acked and the post-write re-read confirms [verifiedBytes] matches what was sent. */
    data class Written(override val id: Int, val verifiedBytes: ByteArray) : CodingEntryOutcome

    /** A prior entry in the same write failed first; this one was never sent. */
    data class NotAttempted(override val id: Int) : CodingEntryOutcome

    /** The write itself was rejected (negative response or transport failure). */
    data class Failed(override val id: Int, val reason: String) : CodingEntryOutcome

    /** The write acked, but the post-write re-read does not match what was sent. */
    data class VerificationMismatch(
        override val id: Int,
        val expected: ByteArray,
        val actual: ByteArray,
    ) : CodingEntryOutcome
}

/** Result of one [DiagnosticsManager.writeCoding] call. */
data class CodingWriteResult(
    /** One outcome per edited id, in the table's `didEntries` order. */
    val outcomes: List<CodingEntryOutcome>,
    /** Every entry in the table, re-read after the write attempt (edited or not) — lets a caller refresh the whole row list, not just the edited ones. */
    val entries: List<CodingEntryRead>,
)
