package nl.jwdr.ooc.protocol.conformance

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.gmlan.DpidRecord
import nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitor
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.Direction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Conformance for GMLAN periodic data (issue #24): while [driveConformance]
 * replays a recorded session's ISO-TP traffic, every recorded broadcast on
 * the secondary CAN ids (request id + 0x300 for GMLAN 0x241..0x25F) must
 * reach a [PeriodicDataMonitor] and decode to the recorded DPID and data
 * bytes — the two listeners share one transport without stealing frames.
 * Skips logs with no periodic-data traffic, and skips entirely without
 * local logs (clean-room pattern, like [RecordedLogConformanceTest]).
 */
@RunWith(Parameterized::class)
class PeriodicDataConformanceTest(private val logFile: File?) {

    @Test
    fun `recorded periodic data reaches a monitor alongside the ISO-TP replay`() = runTest {
        assumeTrue("no local logs in logs/ (clean-room skip)", logFile != null)
        val log = CanLog.parse(logFile!!.readText())
        val secondaryIds = log.frames
            .filter { it.direction == Direction.TX && it.frame.id in 0x241..0x25F }
            .map { it.frame.id + 0x300 }
            .distinct()
        val expected = log.frames
            .filter { it.direction == Direction.RX && it.frame.id in secondaryIds }
        assumeTrue("log has no periodic-data traffic", expected.isNotEmpty())

        val collected = mutableListOf<Pair<Int, DpidRecord>>()
        driveConformance(log, backgroundScope) { transport ->
            for (id in secondaryIds) {
                // UNDISPATCHED: subscribed before playback starts.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    PeriodicDataMonitor(transport, id).records.collect { collected += id to it }
                }
            }
        }
        testScheduler.runCurrent()

        assertEquals(expected.size, collected.size)
        val expectedById = expected.groupBy { it.frame.id }
        val collectedById = collected.groupBy({ it.first }, { it.second })
        for (id in secondaryIds) {
            val expectedForId = expectedById[id].orEmpty()
            val collectedForId = collectedById[id].orEmpty()
            assertEquals("count mismatch for id $id", expectedForId.size, collectedForId.size)
            expectedForId.zip(collectedForId).forEach { (entry, record) ->
                assertEquals(entry.frame.data[0].toInt() and 0xFF, record.dpid)
                assertArrayEquals(
                    entry.frame.data.copyOfRange(1, entry.frame.data.size),
                    record.data,
                )
            }
        }
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
