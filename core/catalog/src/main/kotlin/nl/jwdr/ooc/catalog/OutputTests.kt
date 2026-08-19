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
        get() = bytes.drop(1).take(bytes.firstOrNull() ?: 0)
}

/** One actuator test from an `OutputTests/<KEY>.SCR.txt` file. */
data class OutputTest(
    val title: String,
    val type: OutputTestType,
    val beforeTest: List<CommandRecord> = emptyList(),
    val goActivate: List<CommandRecord> = emptyList(),
    val deActivate: List<CommandRecord> = emptyList(),
    val afterTest: List<CommandRecord> = emptyList(),
    /** `**TAG**` markers: live readouts to show while the test runs. */
    val displayTags: List<String> = emptyList(),
    /** `##…##` operator instructions to confirm before activating. */
    val preTestInstructions: List<String> = emptyList(),
    /** `$$…$$` labels shown while the test is active. */
    val activeLabels: List<String> = emptyList(),
    /** `@@…@@` operator instructions for after the test. */
    val postTestInstructions: List<String> = emptyList(),
)

/** Parsed content of one output-test script file. */
data class OutputTestCatalog(val tests: List<OutputTest>)
