package nl.jwdr.ooc.catalog

/** Interaction model of an output test. */
enum class OutputTestType { ONOFF, UPDOWN, REPEAT }

/**
 * One raw 8-byte command record from an output-test script, preserved
 * verbatim. By observed convention the first byte counts the significant
 * bytes that follow; interpretation is protocol-layer work.
 */
data class CommandRecord(val bytes: List<Int>) {
    val significantBytes: List<Int>
        get() = bytes.drop(1).take(bytes.first())
}

/** One actuator test from an `OutputTests/<KEY>.SCR.txt` file. */
data class OutputTest(
    val title: String,
    val type: OutputTestType,
    val beforeTest: List<CommandRecord> = emptyList(),
    val goActivate: List<CommandRecord> = emptyList(),
    val deActivate: List<CommandRecord> = emptyList(),
    val afterTest: List<CommandRecord> = emptyList(),
)

/** Parsed content of one output-test script file. */
data class OutputTestCatalog(val tests: List<OutputTest>)
