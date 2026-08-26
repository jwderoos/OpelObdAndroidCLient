package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.CodingTable
import nl.jwdr.ooc.catalog.DidEntry
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingWriteTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private val uec = EcuScanTarget(name = "UEC", requestId = 0x250, responseId = 0x650)

    @Test
    fun `writes every edited entry and verifies each against the re-read`() = runTest {
        val table = CodingTable(
            dataIdentifier = 0x1201,
            didEntries = listOf(DidEntry(0x44, 2), DidEntry(0x4C, 2)),
            rows = emptyList(),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x44, 0xAA, 0xBB))
            .respondWith(frame(0x650, 0x02, 0x7B, 0x44))
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x4C, 0xCC, 0xDD))
            .respondWith(frame(0x650, 0x02, 0x7B, 0x4C))
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x44, 0xAA, 0xBB))
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x4C))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x4C, 0xCC, 0xDD))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.writeCoding(
            uec,
            table,
            edits = mapOf(0x44 to bytes(0xAA, 0xBB), 0x4C to bytes(0xCC, 0xDD)),
        )

        assertTrue(result.outcomes.all { it is CodingEntryOutcome.Written })
        assertEquals(
            listOf(0x44 to listOf<Byte>(0xAA.toByte(), 0xBB.toByte()), 0x4C to listOf<Byte>(0xCC.toByte(), 0xDD.toByte())),
            result.outcomes.map { it.id to (it as CodingEntryOutcome.Written).verifiedBytes.toList() },
        )
        assertEquals(
            listOf(0x44 to listOf<Byte>(0xAA.toByte(), 0xBB.toByte()), 0x4C to listOf<Byte>(0xCC.toByte(), 0xDD.toByte())),
            result.entries.map { it.id to it.bytes.toList() },
        )
    }

    @Test
    fun `a failed write stops the batch, leaving later entries not attempted`() = runTest {
        val table = CodingTable(
            dataIdentifier = 0x1201,
            didEntries = listOf(DidEntry(0x44, 2), DidEntry(0x4C, 2)),
            rows = emptyList(),
        )
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 3B 22: conditionsNotCorrect.
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x44, 0xAA, 0xBB))
            .respondWith(frame(0x650, 0x03, 0x7F, 0x3B, 0x22))
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x44, 0x00, 0x00))
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x4C))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x4C, 0x00, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.writeCoding(
            uec,
            table,
            edits = mapOf(0x44 to bytes(0xAA, 0xBB), 0x4C to bytes(0xCC, 0xDD)),
        )

        assertTrue(result.outcomes[0] is CodingEntryOutcome.Failed)
        assertEquals(0x44, result.outcomes[0].id)
        assertTrue(result.outcomes[1] is CodingEntryOutcome.NotAttempted)
        assertEquals(0x4C, result.outcomes[1].id)
        assertFalse(
            "0x4C must never be written once 0x44 failed",
            transport.sentFrames.contains(frame(0x250, 0x04, 0x3B, 0x4C, 0xCC, 0xDD)),
        )
    }

    @Test
    fun `a write that acks but doesn't verify is reported as a mismatch, not a success`() = runTest {
        val table = CodingTable(
            dataIdentifier = 0x1201,
            didEntries = listOf(DidEntry(0x44, 2)),
            rows = emptyList(),
        )
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x04, 0x3B, 0x44, 0xAA, 0xBB))
            .respondWith(frame(0x650, 0x02, 0x7B, 0x44))
        // Re-read disagrees with what was written.
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x44, 0x00, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.writeCoding(uec, table, edits = mapOf(0x44 to bytes(0xAA, 0xBB)))

        val outcome = result.outcomes.single() as CodingEntryOutcome.VerificationMismatch
        assertEquals(0x44, outcome.id)
        assertEquals(listOf<Byte>(0xAA.toByte(), 0xBB.toByte()), outcome.expected.toList())
        assertEquals(listOf<Byte>(0x00, 0x00), outcome.actual.toList())
    }

    @Test
    fun `rejects an edit for an id the table doesn't define`() = runTest {
        val table = CodingTable(0x1201, listOf(DidEntry(0x44, 2)), emptyList())
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))

        val e = runCatching {
            manager.writeCoding(uec, table, edits = mapOf(0x99 to bytes(0x00, 0x00)))
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
    }
}
