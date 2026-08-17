package nl.jwdr.ooc.protocol.conformance

import java.io.File
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanLog
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Conformance suite over locally recorded OP-COM sessions (issue #7): each
 * `*.canlog` in the repo-root `logs/` directory is replayed through the real
 * protocol stack by [driveConformance], so every recorded tx frame —
 * segmentation, flow control, padding, sequence numbers — must be reproduced
 * byte for byte, and every recorded response must reassemble exactly.
 *
 * The logs contain real vehicle data and are git-ignored (clean-room
 * pattern): when the directory is absent or empty the suite skips cleanly.
 * The directory's location arrives via the `ooc.canlogDir` system property
 * set in this module's build script; convert new OP-COM captures with
 * `tools/opcom-debug-to-canlog.py`.
 */
@RunWith(Parameterized::class)
class RecordedLogConformanceTest(private val logFile: File?) {

    @Test
    fun `replays the recorded session through the protocol stack`() = runTest {
        assumeTrue("no local logs in logs/ (clean-room skip)", logFile != null)

        driveConformance(CanLog.parse(logFile!!.readText()), backgroundScope)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun localLogs(): List<Array<File?>> {
            val dir = System.getProperty("ooc.canlogDir")?.let(::File)
            val logs = dir?.listFiles { file -> file.name.endsWith(".canlog") }
                ?.sortedBy { it.name }
                .orEmpty()
            // Parameterized needs at least one entry; a null sentinel keeps
            // the suite visible as skipped when no local logs exist.
            return if (logs.isEmpty()) listOf(arrayOf(null)) else logs.map { arrayOf<File?>(it) }
        }
    }
}
