package nl.jwdr.ooc.protocol.conformance

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.transport.CanLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the conformance machinery against the committed synthetic
 * fixture, so the suite itself is covered in CI where the local vehicle
 * logs (see [RecordedLogConformanceTest]) are absent.
 */
class SyntheticConformanceTest {

    private fun fixture(): CanLog {
        val resource = checkNotNull(javaClass.getResource("/conformance/synthetic-conformance.canlog"))
        return CanLog.parse(resource.readText())
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `reconstructs the tester operations of the fixture`() {
        val ops = reconstructTesterOps(fixture())

        assertEquals(9, ops.size)
        assertEquals(0x101, (ops[0] as TesterOp.RawSend).frame.id)
        (ops[1] as TesterOp.Send).let {
            assertEquals(IsoTpAddress(0x242, 0x642), it.address)
            assertArrayEquals(bytes(0x20), it.payload)
        }
        (ops[2] as TesterOp.Send).let {
            assertEquals(IsoTpAddress(0x241, 0x641), it.address)
            assertArrayEquals(bytes(0x1A, 0x90), it.payload)
        }
        (ops[3] as TesterOp.Expect).let {
            assertEquals(IsoTpAddress(0x241, 0x641), it.address)
            assertArrayEquals(bytes(0x5A, 0x90, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48), it.payload)
        }
        (ops[4] as TesterOp.Send).let {
            assertEquals(IsoTpAddress(0x7E0, 0x7E8), it.address)
            assertArrayEquals(bytes(0x3B, 0x99, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07), it.payload)
        }
        (ops[5] as TesterOp.Expect).let {
            assertEquals(IsoTpAddress(0x7E0, 0x7E8), it.address)
            assertArrayEquals(bytes(0x7B), it.payload)
        }
        (ops[6] as TesterOp.Expect).let {
            assertEquals(IsoTpAddress(0x241, 0x641), it.address)
            assertArrayEquals(bytes(0x5A, 0x99), it.payload)
        }
        // Output-test device control exchange (as recorded on body ECUs:
        // single-frame 0xAE request, 0xEE echo of the device id).
        (ops[7] as TesterOp.Send).let {
            assertEquals(IsoTpAddress(0x241, 0x641), it.address)
            assertArrayEquals(bytes(0xAE, 0x02, 0x02, 0x00, 0x00, 0x00), it.payload)
        }
        (ops[8] as TesterOp.Expect).let {
            assertEquals(IsoTpAddress(0x241, 0x641), it.address)
            assertArrayEquals(bytes(0xEE, 0x02), it.payload)
        }
    }

    @Test
    fun `replays the fixture through the protocol stack`() = runTest {
        val ops = driveConformance(fixture(), backgroundScope)

        assertEquals(9, ops.size)
    }

    @Test
    fun `a stack deviation from the recording fails the replay`() = runTest {
        // Tamper with the recorded tester flow control (block size 1 instead
        // of 0): the stack's own flow control no longer matches, and the
        // gating transport must reject the replay.
        val tampered = fixture().let { log ->
            CanLog(
                log.metadata,
                log.frames.map { entry ->
                    if (entry.direction == nl.jwdr.ooc.transport.Direction.TX &&
                        entry.frame.id == 0x241 &&
                        entry.frame.data[0].toInt() == 0x30
                    ) {
                        entry.copy(
                            frame = entry.frame.copy(
                                data = bytes(0x30, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                            ),
                        )
                    } else {
                        entry
                    }
                },
            )
        }

        val thrown = runCatching { driveConformance(tampered, backgroundScope) }.exceptionOrNull()
        assertTrue("expected the replay to reject the deviation, got $thrown", thrown is IllegalStateException)
    }
}
